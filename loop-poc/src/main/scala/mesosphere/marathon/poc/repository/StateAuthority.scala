package mesosphere.marathon
package poc.repository

import akka.stream.scaladsl.Flow
import java.util.UUID
import mesosphere.marathon.poc.state._
import monocle.macros.syntax.lens._
import scala.collection.immutable.Queue

sealed trait Effect
object Effect {
  // reason ideally is modeled with more detail than string
  case class PublishResult(requestId: Long, result: Either[Rejection, Result]) extends Effect
  case class PersistUpdates(version: Long, updates: Seq[StateTransition]) extends Effect
  case class PublishUpdates(updates: Seq[StateTransition]) extends Effect
}
case class PendingUpdate(version: Long, requestId: Long, updates: Seq[StateTransition])

case class MarathonState(
    rootGroup: RootGroup,
    instances: InstanceSet)
object MarathonState {
  def empty = MarathonState(rootGroup = RootGroup.empty, instances = InstanceSet.empty)
}

case class StateFrame(
    version: Long,
    pendingUpdates: Queue[PendingUpdate],
    state: MarathonState)

object StateFrame {
  val empty = StateFrame(
    state = MarathonState.empty,
    version = 1,
    pendingUpdates = Queue.empty)
}

sealed trait StateAuthorityInputEvent

/**
  * Notify that a version is persisted. Should only be submitted by storage component.
  */
private[repository] case class MarkPersisted(version: Long) extends StateAuthorityInputEvent

case class CommandRequest(requestId: Long, command: StateCommand) extends StateAuthorityInputEvent

sealed trait StateCommand
object StateCommand {
  case class PutApp(runSpec: RunSpec) extends StateCommand

  case class AddInstance(instance: Instance) extends StateCommand
}

case class Rejection(reason: String)

case class Result(
    stateTransitions: Seq[StateTransition])

sealed trait StateTransition
object StateTransition {
  case class RunSpecUpdated(ref: RunSpecRef, runSpec: Option[RunSpec]) extends StateTransition
  case class InstanceUpdated(instanceId: UUID, instance: Option[Instance]) extends StateTransition

  // extract and share with Marathon scheduler
  def applyTransitions(frame: MarathonState, effects: Seq[StateTransition]): MarathonState = {
    effects.foldLeft(frame) {
      case (frame, update: InstanceUpdated) =>
        update.instance match {
          case Some(instance) =>
            frame.lens(_.instances).modify(_.withInstance(instance))
          case None =>
            frame.lens(_.instances).modify(_.withoutInstance(update.instanceId))
        }
      case (frame, update: RunSpecUpdated) =>
        update.runSpec match {
          case Some(runSpec) =>
            frame.lens(_.rootGroup).modify(_.withApp(runSpec))
          case None =>
            frame.lens(_.rootGroup).modify(_.withoutApp(update.ref))
        }
    }
  }
}

object StateAuthority {
  val commandProcessorFlow = Flow[StateAuthorityInputEvent].statefulMapConcat { () =>
    var currentFrame: StateFrame = StateFrame.empty

    { event =>

      val (effects, nextFrame) = StateAuthority.submitEvent(currentFrame, event)
      currentFrame = nextFrame
      effects
    }
  }

  /**
    * Given a command and a requestId, return some effects and the next frame
    */
  def submitEvent(frame: StateFrame, event: StateAuthorityInputEvent): (Seq[Effect], StateFrame) = event match {
    case CommandRequest(requestId, command) =>
      applyCommand(frame, command) match {
        case result @ Left(failure) =>
          // issue failure for requestId
          (
            List(Effect.PublishResult(requestId, result)),
            frame)
        case Right(result) =>

          //    frameWithUpdate.lens(_.version).modify(_ + 1)

          val nextFrame = frame
            .lens(_.version).modify(_ + 1)
            .lens(_.state).modify(StateTransition.applyTransitions(_, result.stateTransitions))
          val withUpdates = nextFrame.lens(_.pendingUpdates).modify { pendingUpdates =>
            pendingUpdates.enqueue(PendingUpdate(nextFrame.version, requestId, result.stateTransitions))
          }

          (
            List(Effect.PersistUpdates(nextFrame.version, result.stateTransitions)),
            withUpdates)
      }
    case MarkPersisted(version) =>
      val updates = frame.pendingUpdates.iterator.takeWhile { _.version <= version }.toList
      val nextFrame = frame.lens(_.pendingUpdates).modify(_.drop(updates.size))

      val effects: List[Effect] = Effect.PublishUpdates(updates.flatMap(_.updates)) ::
        updates.map { u => Effect.PublishResult(u.requestId, Right(Result(u.updates))) }

      (effects, nextFrame)
  }

  def applyCommand(frame: StateFrame, command: StateCommand): Either[Rejection, Result] = {
    command match {
      case addApp: StateCommand.PutApp =>
        // we'd apply a validation here
        Right(
          Result(
            Seq(
              StateTransition.RunSpecUpdated(ref = addApp.runSpec.ref, runSpec = Some(addApp.runSpec)))))
      case addInstance: StateCommand.AddInstance =>
        if (frame.state.rootGroup.get(addInstance.instance.runSpec).isEmpty)
          Left(
            Rejection(s"No runSpec ${addInstance.instance.runSpec}"))
        else
          Right(
            Result(
              Seq(
                StateTransition.InstanceUpdated(addInstance.instance.instanceId, Some(addInstance.instance)))))

    }
  }
}
