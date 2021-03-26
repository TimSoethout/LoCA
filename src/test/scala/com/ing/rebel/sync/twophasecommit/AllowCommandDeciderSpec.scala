package com.ing.rebel.sync.twophasecommit

import cats.scalatest.ValidatedMatchers
import com.ing.example.Account
import com.ing.example.Account._
import com.ing.rebel.Ibans._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider._
import com.ing.rebel.sync.pathsensitive.{AllowCommandDecider, DynamicPsacCommandDecider, StaticCommandDecider}
import com.ing.rebel.{Ibans, Initialised, RebelConditionCheck, RebelData, RebelDomainEvent}
import com.ing.util.TestBase
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import squants.market.EUR

//noinspection ScalaStyle
class AllowCommandDeciderSpec extends TestBase with ScalaCheckPropertyChecks with ValidatedMatchers {

  val decider: AllowCommandDecider[Account.type] = new DynamicPsacCommandDecider(5, Account.Logic)

  // Hacky way to make sure the "old" checking of the whole tree is not hit, although a bit whitebox
  val onlyStaticDecider: StaticCommandDecider[Account.type] = new StaticCommandDecider(5, Account.Logic)(
    (currentState: Account.Logic.RState, currentData: Account.Logic.RData, relevantTransactions: Seq[Account.Logic.RDomainEvent], incomingEvent: Account.Logic.RDomainEvent) => fail("Should not use the PSAC possible outcomes tree and precondition checks"))

  "allowCommand" should "Accept when all possible states allow (success preconditions), using static information only" in {
    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(), RebelDomainEvent(Deposit(EUR(10)))) shouldBe DynamicAccept

    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Deposit(EUR(10)))) shouldBe StaticAccept

    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(
      RebelDomainEvent(Deposit(EUR(20))),
      RebelDomainEvent(Deposit(EUR(30)))
    ), RebelDomainEvent(Deposit(EUR(10)))) shouldBe StaticAccept
  }

  it should "accept when all possible states disallow (fail preconditions), using static information only" in {
    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(), RebelDomainEvent(Withdraw(EUR(1000)))) shouldBe a[DynamicReject]
  }

  it should "accept when all possible states disallow (fail preconditions)" in {
    decider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Withdraw(EUR(1000)))) shouldBe a[DynamicReject]
    decider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(
      RebelDomainEvent(Deposit(EUR(20))),
      RebelDomainEvent(Deposit(EUR(30)))
    ), RebelDomainEvent(Withdraw(EUR(1000)))) shouldBe a[DynamicReject]
  }

  it should "decline when some, but not all, possible states disallow (fail preconditions)" in {
    decider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Deposit(EUR(100)))), RebelDomainEvent(Withdraw(EUR(150)))) shouldBe a[Delay]
  }

  it should "fail fast on always incompatible states" in {
    onlyStaticDecider.allowCommand(Opened, Initialised(AccountData(Some(EUR(100)))), Seq(RebelDomainEvent(Unblock)), RebelDomainEvent(OpenAccount(testIban1, EUR(1000)))) shouldBe StaticReject
  }
}
