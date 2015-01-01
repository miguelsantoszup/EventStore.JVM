package eventstore
package operations

import eventstore.NotHandled.{ NotReady, TooBusy }
import eventstore.tcp.PackOut
import eventstore.operations.OnIncoming._
import eventstore.operations.{ SubscriptionOperation => SO }
import scala.util.{ Try, Success, Failure }

class SubscriptionOperationSpec extends OperationSpec {
  val streamId = EventStream.Id("streamId")
  val streams = Seq(EventStream.All, streamId)

  "SubscriptionOperation when subscribing" should {
    "return id equal to correlationId" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.id mustEqual pack.correlationId
      }
    }

    "drop OutFunc on disconnected" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val expected = operation.copy(outFunc = None)
        val actual = operation.disconnected mustEqual OnDisconnected.Continue(expected)
        there were noCallsTo(outFunc, inFunc)
      }
    }

    "save new OutFunc on connected and retry" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val newOutFunc = mock[OutFunc]
        val actual = operation.copy(outFunc = None).connected(newOutFunc)
        actual must beSome
        actual.get.outFunc mustEqual Some(newOutFunc)
        there were noCallsTo(outFunc, inFunc)
        there was one(newOutFunc).apply(pack)
      }
    }

    "replace OutFunc on connected and retry" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val newOutFunc = mock[OutFunc]
        val actual = operation.copy(outFunc = None).connected(newOutFunc)
        actual must beSome
        actual.get.outFunc mustEqual Some(newOutFunc)
        there were noCallsTo(outFunc, inFunc)
        there was one(newOutFunc).apply(pack)
      }
    }

    "unsubscribe on clientTerminated" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.clientTerminated must beSome(pack.copy(message = Unsubscribe))
        there were noCallsTo(inFunc)
      }
    }

    "ignore out messages except Unsubscribe" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val outs = Seq(
          subscribeTo,
          ReadEvent(streamId),
          ReadStreamEvents(streamId),
          ReadAllEvents(),
          Ping,
          HeartbeatRequest,
          Authenticate,
          ScavengeDatabase)
        foreach(outs) { x => operation.inspectOut.isDefinedAt(x) must beFalse }

        operation.inspectOut(Unsubscribe) mustEqual OnOutgoing.Stop(unsubscribe, Try(Unsubscribed))
      }
    }

    "forward new events" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val event = eventAppeared(streamId)
        operation.inspectIn(Success(event)) mustEqual Continue(operation, Success(event))
      }
    }

    "stop on unexpected events" in new SubscribingScope()(streamId) {
      val event = eventAppeared(EventStream.Id("unexpected"))
      operation.inspectIn(Success(event)) must beLike {
        case Stop(Failure(_: CommandNotExpectedException)) => ok
      }
    }

    "become subscribed on success" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Success(subscribeCompleted)) mustEqual Continue(
          SubscriptionOperation.Subscribed(subscribeTo, pack, client, inFunc, outFunc, 1),
          Success(subscribeCompleted))
      }
    }

    "stay on success if disconnected" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val disconnected = operation.copy(outFunc = None)
        disconnected.inspectIn(Success(subscribeCompleted)) mustEqual Ignore
      }
    }

    "stop on expected error" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(SubscriptionDropped.AccessDenied)) must beLike {
          case Stop(Failure(_: AccessDeniedException)) => ok
        }
      }
    }

    "retry on NotReady" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(NotHandled(NotReady))) mustEqual Retry(operation, pack)
      }
    }

    "retry on TooBusy" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(NotHandled(TooBusy))) mustEqual Retry(operation, pack)
      }
    }

    "stop on OperationTimedOut" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(OperationTimedOut)) mustEqual Stop(OperationTimeoutException(pack))
      }
    }

    "stop on NotAuthenticated" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(NotAuthenticated)) must beLike {
          case Stop(Failure(_: NotAuthenticatedException)) => ok
        }
      }
    }

    "stop on BadRequest" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(BadRequest)) must beLike {
          case Stop(Failure(_: ServerErrorException)) => ok
        }
      }
    }

    "stop on unexpected" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        val unexpected = stream match {
          case _: EventStream.Id => SubscribeToAllCompleted(0)
          case _                 => SubscribeToStreamCompleted(0)
        }
        operation.inspectIn(Success(unexpected)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on unexpected error" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.inspectIn(Failure(ReadEventError.StreamDeleted)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "return 0 for version" in foreach(streams) { implicit stream =>
      new SubscribingScope {
        operation.version mustEqual 0
        val o1 = operation.disconnected must beLike {
          case OnDisconnected.Continue(x) if x.version == 0 =>
            x.connected(outFunc).get.version mustEqual 0
            ok
        }
      }
    }
  }

  "SubscriptionOperation when subscribed" should {
    "return id equal to correlationId" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.id mustEqual pack.correlationId
      }
    }

    "become subscribing on disconnected" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        val actual = operation.disconnected must beLike {
          case OnDisconnected.Continue(SubscriptionOperation.Subscribing(_, _, _, _, None, 1)) => ok
        }
        there were noCallsTo(outFunc, inFunc)
      }
    }

    "become subscribing on connected and retry" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        val newOutFunc = mock[OutFunc]
        val actual = operation.connected(newOutFunc)
        actual must beSome
        actual.get.outFunc mustEqual Some(newOutFunc)
        actual.get.version mustEqual 1
        there were noCallsTo(outFunc, inFunc)
        there was one(newOutFunc).apply(pack)
      }
    }

    "unsubscribe on clientTerminated" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.clientTerminated must beSome(pack.copy(message = Unsubscribe))
        there were noCallsTo(inFunc)
      }
    }

    "ignore out messages except Unsubscribe" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        val outs = Seq(
          subscribeTo,
          ReadEvent(streamId),
          ReadStreamEvents(streamId),
          ReadAllEvents(),
          Ping,
          HeartbeatRequest,
          Authenticate,
          ScavengeDatabase)
        foreach(outs) { x => operation.inspectOut.isDefinedAt(x) must beFalse }

        val unsubscribing = operation.inspectOut(Unsubscribe)
        unsubscribing must beLike {
          case OnOutgoing.Continue(x, `unsubscribe`) if x.version == 1 => ok
        }
      }
    }

    "forward new events" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        val event = eventAppeared(streamId)
        operation.inspectIn(Success(event)) mustEqual Continue(operation, Success(event))
      }
    }

    "stop on unexpected events" in new SubscribedScope()(streamId) {
      val event = eventAppeared(EventStream.Id("unexpected"))
      operation.inspectIn(Success(event)) must beLike {
        case Stop(Failure(_: CommandNotExpectedException)) => ok
      }
    }

    "stop on AccessDenied" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(SubscriptionDropped.AccessDenied)) must beLike {
          case Stop(Failure(_: AccessDeniedException)) => ok
        }
      }
    }

    "stop on Unsubscribed" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Success(Unsubscribed)) mustEqual Stop(Unsubscribed)
      }
    }

    "stop on NotReady" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(NotHandled(NotReady))) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on TooBusy" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(NotHandled(TooBusy))) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on OperationTimedOut" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(OperationTimedOut)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on NotAuthenticated" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(NotAuthenticated)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on BadRequest" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(BadRequest)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on unexpected" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Success(Pong)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on unexpected error" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.inspectIn(Failure(ReadEventError.StreamDeleted)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "return 0 for version" in foreach(streams) { implicit stream =>
      new SubscribedScope {
        operation.version mustEqual 0
        val o1 = operation.disconnected must beLike {
          case OnDisconnected.Continue(x) if x.version == 1 => ok
        }
      }
    }
  }

  "SubscriptionOperation when unsubscribing" should {
    "return id equal to correlationId" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.id mustEqual pack.correlationId
      }
    }

    "stop on disconnected" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.disconnected mustEqual OnDisconnected.Stop(Success(Unsubscribed))
      }
    }

    "stop on connected" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        val newOutFunc = mock[OutFunc]
        operation.connected(newOutFunc) must beNone
        thereWasStop(Success(Unsubscribed))
      }
    }

    "stop on clientTerminated" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.clientTerminated must beNone
      }
    }

    "ignore out messages" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectOut mustEqual PartialFunction.empty
        operation.inspectOut.isDefinedAt(Unsubscribe) must beFalse
        there were noCallsTo(outFunc, inFunc)
      }
    }

    "stop on success" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Success(Unsubscribed)) mustEqual Stop(Unsubscribed)
      }
    }

    "stop on expected error" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(SubscriptionDropped.AccessDenied)) must beLike {
          case Stop(Failure(_: AccessDeniedException)) => ok
        }
      }
    }

    "retry on NotReady" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(NotHandled(NotReady))) mustEqual Retry(operation, pack)
      }
    }

    "retry on TooBusy" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(NotHandled(TooBusy))) mustEqual Retry(operation, pack)
      }
    }

    "stop on OperationTimedOut" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(OperationTimedOut)) mustEqual Stop(OperationTimeoutException(pack))
      }
    }

    "stop on NotAuthenticated" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(NotAuthenticated)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on BadRequest" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(BadRequest)) must beLike {
          case Stop(Failure(_: ServerErrorException)) => ok
        }
      }
    }

    "stop on unexpected" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        val unexpected = stream match {
          case x: EventStream.Id => SubscribeToStreamCompleted(0)
          case _                 => SubscribeToAllCompleted(0)
        }
        operation.inspectIn(Success(unexpected)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "stop on unexpected error" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Failure(ReadEventError.StreamDeleted)) must beLike {
          case Stop(Failure(_: CommandNotExpectedException)) => ok
        }
      }
    }

    "ignore new events" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.inspectIn(Success(eventAppeared(streamId))) mustEqual Ignore
      }
    }

    "stop on unexpected events" in new SubscribedScope()(streamId) {
      val event = eventAppeared(EventStream.Id("unexpected"))
      operation.inspectIn(Success(event)) must beLike {
        case Stop(Failure(_: CommandNotExpectedException)) => ok
      }
    }

    "return 0 for version" in foreach(streams) { implicit stream =>
      new UnsubscribingScope {
        operation.version mustEqual 0
      }
    }
  }

  private trait SubscriptionScope extends OperationScope {
    def eventAppeared(streamId: EventStream.Id) = {
      val event = EventRecord(streamId, EventNumber.First, EventData("test"))
      val indexedEvent = IndexedEvent(event, Position.First)
      StreamEventAppeared(indexedEvent)
    }

    def stream: EventStream

    lazy val streamId = stream match {
      case x: EventStream.Id => x
      case _                 => SubscriptionOperationSpec.this.streamId
    }
  }

  private abstract class SubscribingScope(implicit val stream: EventStream) extends SubscriptionScope {
    val subscribeTo = SubscribeTo(stream)
    val pack = PackOut(subscribeTo)
    val unsubscribe = PackOut(Unsubscribe, pack.correlationId, pack.credentials)
    val operation = SO.Subscribing(subscribeTo, pack, client, inFunc, Some(outFunc), 0)

    lazy val subscribeCompleted = stream match {
      case x: EventStream.Id => SubscribeToStreamCompleted(0)
      case _                 => SubscribeToAllCompleted(0)
    }
  }

  private abstract class SubscribedScope(implicit val stream: EventStream) extends SubscriptionScope {
    val subscribeTo = SubscribeTo(stream)
    val pack = PackOut(subscribeTo)
    val unsubscribe = PackOut(Unsubscribe, pack.correlationId, pack.credentials)
    val operation = SO.Subscribed(subscribeTo, pack, client, inFunc, outFunc, 0)
  }

  private abstract class UnsubscribingScope(implicit val stream: EventStream) extends SubscriptionScope {
    val subscribeTo = SubscribeTo(stream)
    val pack = PackOut(Unsubscribe)
    val operation = SO.Unsubscribing(stream, pack, client, inFunc, outFunc, 0)
  }
}