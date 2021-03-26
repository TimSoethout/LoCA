package com.ing.rebel.sync.pathsensitive

import cats.data.NonEmptyList
import cats.data.Validated.Invalid
import cats.implicits._
import com.ing.rebel.config.{Dynamic, Locking, RebelConfig, StaticThenDynamic, StaticThenLocking}
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider._
import com.ing.rebel.{RebelCheck, RebelConditionCheck, RebelDomainEvent, RebelError, RebelErrors, RebelState, SpecificationEvent}

object AllowCommandDecider {
  sealed trait AllowCommandDecision
  sealed trait Accept extends AllowCommandDecision // Direct accept the event, potentially bootstrapping 2PC
  sealed trait Reject extends AllowCommandDecision // Direct Decline, fail fast the event, shortcircuit 2PC
  // Allowed to be no reasons
  sealed trait Delay extends AllowCommandDecision // Delay, fall back to waiting/locking, vanilla 2PC

  case object StaticAccept extends Accept // Accepted with static knowledge
  case object DynamicAccept extends Accept // Accepted with dynamic knowledge (using possible outcomes)

  case object StaticReject extends Reject
  case class DynamicReject(errors: RebelErrors) extends Reject // potentially case class with RebelErrors, with why declined.

  case class FunctionalDelay(errors: RebelErrors) extends Delay
  // Delayed because of incompatibility with other in progress action
  case object TwoPCLockDelay extends Delay // TwoPC only allows one parallel transaction
  case object MaxInProgressReachedDelay extends Delay // Configured max reached

  def allowCommandDecider[S <: Specification](config: RebelConfig, specification: RebelSpecification[S]): AllowCommandDecider[S] =
    config.sync.commandDecider match {
      case Locking           => new TwoPCCommandDecider(specification)
      case Dynamic           => new DynamicPsacCommandDecider[S](config.sync.maxTransactionsInProgress, specification)
      case StaticThenDynamic => new StaticCommandDecider[S](config.sync.maxTransactionsInProgress, specification)()
      case StaticThenLocking => new StaticCommandDecider[S](config.sync.maxTransactionsInProgress, specification)(new TwoPCCommandDecider(specification))
    }
}

/**
  * Decides if command is allowed to start at this moment
  *
  * @tparam S spec
  */
trait AllowCommandDecider[S <: Specification] {
  def allowCommand(currentState: S#State, currentData: S#RData,
                   relevantTransactions: Seq[S#RDomainEvent], incomingEvent: S#RDomainEvent): AllowCommandDecision
}

class TwoPCCommandDecider[S <: Specification](specification: RebelSpecification[S]) extends AllowCommandDecider[S] {
  def allowCommand(currentState: specification.RState, currentData: specification.RData,
                   relevantTransactions: Seq[specification.RDomainEvent], incomingEvent: specification.RDomainEvent): AllowCommandDecision = {
    if (relevantTransactions.nonEmpty) {
      // "2PC does not support in progress transactions"
      TwoPCLockDelay
    } else {
      (specification.nextStateGeneric(currentState, incomingEvent.specEvent) <*
        specification.checkPreConditions(currentData, incomingEvent.timestamp)(incomingEvent.specEvent))
        .fold(DynamicReject, _ => DynamicAccept)
    }
  }
}

class StaticCommandDecider[S <: Specification](maxTransactionsInProgress: Int, specification: RebelSpecification[S])
                                              (fallBackCommandDecider: AllowCommandDecider[S] = new DynamicPsacCommandDecider(maxTransactionsInProgress, specification))
  extends AllowCommandDecider[S] {

  override def allowCommand(currentState: specification.RState, currentData: specification.RData,
                            relevantTransactions: Seq[specification.RDomainEvent], incomingEvent: specification.RDomainEvent): AllowCommandDecision = {
    // If no actions in progress
    if (relevantTransactions.isEmpty) {
      specification.tryTransition(currentState, currentData, incomingEvent).fold(DynamicReject, _ => DynamicAccept): AllowCommandDecision
    } else {
      // check if compatible with all in progress actions
      val compatibleWithAllInProgress: Boolean =
        relevantTransactions.forall(inProgressAction => specification.alwaysCompatibleEvents.isDefinedAt(inProgressAction.specEvent, incomingEvent.specEvent))

      if (compatibleWithAllInProgress) {
        // all are compatible
        StaticAccept
        // check if incompatible with all to fail fast
      } else if (relevantTransactions.forall(inProgressAction => specification.failFastEvents.isDefinedAt(inProgressAction.specEvent, incomingEvent.specEvent))) {
        StaticReject
      } else {
        // not always allowed, and not declined fast, falling back to checking all states
        fallBackCommandDecider.allowCommand(currentState, currentData, relevantTransactions, incomingEvent)
      }
    }
  }
}

class DynamicPsacCommandDecider[S <: Specification](maxTransactionsInProgress: Int, specification: RebelSpecification[S]) extends AllowCommandDecider[S] {

  private object Types {
    type State = (specification.RState, specification.RData)
    type EventEffector = (State, specification.RDomainEvent) => State
    type PossibleOutcomes = NonEmptyList[State]
  }

  import Types._

  private val eventEffector: EventEffector =
    (state, event) => {
      val newState = specification.tryTransition(state._1, state._2, event)
      newState.getOrElse {
        throw new NotImplementedError(
          s"Should not occur for $event $newState in currentState/Data: ${state._2}/${state._1}")
      }
    }

  private def newPossibleOutcomes(outcomes: PossibleOutcomes, event: specification.RDomainEvent): PossibleOutcomes = {
    outcomes.concatNel(outcomes.map(state => eventEffector(state, event)))
  }

  override def allowCommand(currentState: specification.RState, currentData: specification.RData,
                            relevantTransactions: Seq[specification.RDomainEvent], incomingEvent: specification.RDomainEvent): AllowCommandDecision = {
    if (relevantTransactions.size >= maxTransactionsInProgress) {
      MaxInProgressReachedDelay
    } else {
      // Algorithm:
      // Iterate over all possible state/data outcomes. Try apply command.
      // All OK => Accept / OK
      // All NOK => Accept / NOK
      // otherwise => Delay / stash
      // Future work: reorder

      val currentPossibleOutcomes: PossibleOutcomes = NonEmptyList.of((currentState, currentData))

      val possibleOutcomes: PossibleOutcomes = relevantTransactions.foldLeft(currentPossibleOutcomes) {
        // TODO we can cache newPossibleOutcomes, because start of list will be the same if no actions completed in the mean time since previous check
        case (outcomes, event) => newPossibleOutcomes(outcomes, event)
      }

      val newActionInTentativeStates: NonEmptyList[RebelCheck[(specification.RState, specification.RData)]] =
        possibleOutcomes.map { case (state, data) => specification.tryTransition(state, data, incomingEvent) }

      val allOk: Boolean = newActionInTentativeStates.forall(_.isValid)
      if (allOk) {
        DynamicAccept
      } else {
        val noneOk: Boolean = newActionInTentativeStates.forall(_.isInvalid)
        val flattened = newActionInTentativeStates.toList.sequence_
        // should be invalid, because at least some invalid
        val errors = flattened.asInstanceOf[Invalid[RebelErrors]].e
        if (noneOk) {
          DynamicReject(errors)
        } else {
          assert(flattened.isInvalid, "If not allOk or noneOk, there should be at least one error message")
          FunctionalDelay(errors)
        }
      }
    }
  }
}