// Generated @ 05-01-2017 15:18:45
package com.ing.corebank.rebel.sharding

import akka.actor.{ActorRef, ExtendedActorSystem, ExtensionKey, Props}
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel._
import io.circe.generic.auto._
import com.ing.corebank.rebel.simple_transaction._
import com.ing.corebank.rebel.simple_transaction.Account._
import com.ing.corebank.rebel.simple_transaction.actor._
import com.ing.rebel.specification.SpecificationInfo
import io.circe.Decoder

import scala.reflect.ClassTag

object AccountSharding extends ExtensionKey[AccountSharding]
class AccountSharding(system: ExtendedActorSystem) extends RebelShardingExtension(system) {
  override type Spec = Account.type
  override val entryProps: Props = AccountActor.props

  override def specClassTag: ClassTag[Event] = implicitly

  override val eventDecoder: Decoder[spec.Event] = implicitly

  override implicit def specInfo: SpecificationInfo[Account.type] = Account.specInfo
}
