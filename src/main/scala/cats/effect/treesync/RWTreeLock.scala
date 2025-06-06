package cats.effect.treesync

import cats.effect.kernel._
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.collection.immutable.{Queue => Q}

trait RWTreeLock[F[_]] {
  def readLock(path: List[String]): Resource[F, Unit]
  def writeLock(path: List[String]): Resource[F, Unit]

  private[treesync] def acquire(path: List[String], mode: GrantMode): F[Unit]
  private[treesync] def release(path: List[String], mode: GrantMode): F[Unit]
}

object RWTreeLock {
  implicit private class IteratorOps[T](it: Iterator[T]) {
    def flatMapWithState[S](
        initialState: S,
        shortCircuit: S => Boolean = (_: S) => false
    )(f: (S, T) => (S, IterableOnce[T])): Iterator[T] =
      if (it.nonEmpty) {
        it.scanLeft((initialState, (None: IterableOnce[T]))) {
          case ((s, _), t) =>
            f(s, t)
        }.takeWhile { case (s, _) => !shortCircuit(s) }
          .flatMap(_._2)
      } else it
  }

  def apply[F[_]](maxConcurrent: Int = 0)(implicit F: GenConcurrent[F, ?]): F[RWTreeLock[F]] = {
    val impl = new Impl[F]
    F.ref(impl.initialState).map(impl.rwLockTree(_, maxConcurrent))
  }

  private class Impl[F[_]](implicit F: GenConcurrent[F, ?]) {
    case class Request(gate: Deferred[F, Unit], path: List[String], mode: GrantMode) {
      def sameAs(r: Request): Boolean = r.gate == gate
      def wait_ = gate.get
      def complete = gate.complete(())
    }
    def newRequest(path: List[String], mode: GrantMode): F[Request] =
      F.deferred[Unit].map(Request(_, path, mode))

    case class State(grants: RWGrantTree, waiting: Q[Request])
    def initialState = State(RWGrantTree.empty, Q())

    sealed trait Decision
    case object Wait extends Decision
    case object Done extends Decision

    def rwLockTree(stateRef: Ref[F, State], maxConcurrent: Int) = new RWTreeLock[F] {
      def acquire(path: List[String], mode: GrantMode): F[Unit] = F.uncancelable { poll =>
        newRequest(path, mode).flatMap { req =>
          stateRef.modify { state =>
            val (newState, decision) =
              if (state.grants.canGrant(path, mode, maxConcurrent))
                state.copy(grants = state.grants.acquireGrant(path, mode)) -> Done
              else {
                val updatedState = state.copy(
                  grants = state.grants.updateWaiting(path, diff = 1),
                  waiting = state.waiting.enqueue(req)
                )
                updatedState -> Wait
              }

            val cleanup = stateRef.modify { state =>
              val cleanedWaiting = state.waiting.filterNot(_.sameAs(req))
              val releaseAction =
                if (cleanedWaiting.size != state.waiting.size) {
                  release(path, mode)
                } else F.unit
              state.copy(waiting = cleanedWaiting) -> releaseAction
            }.flatten

            val action = decision match {
              case Done => F.unit
              case Wait => poll(req.wait_).onCancel(cleanup)
            }

            newState -> action
          }
        }
      }.flatten

      def release(path: List[String], mode: GrantMode): F[Unit] = stateRef.flatModify {
        currentState =>
          val newState =
            currentState.copy(grants = currentState.grants.releaseGrant(path, mode))

          if (!newState.waiting.isEmpty) {
            val wakeableReq = newState.waiting.iterator
              .flatMapWithState(
                RWGrantCandidateState.from(newState.grants),
                shortCircuit = (s: RWGrantCandidateState) => s.canShortCircuit
              ) { case (candidateState, candidate) =>
                val (updatedCandidateState, canGrant) = candidateState.evaluateCandidate(candidate.path, candidate.mode)
                val grantableCandidate = Option.when(canGrant)(candidate)

                updatedCandidateState -> grantableCandidate
              }
              .toList

            val (finalState, actions) =
              wakeableReq.foldLeft(newState -> Vector[F[Unit]]()) {
                case ((workState, actionAcc), req) =>
                  if (workState.grants.canGrant(req.path, req.mode, maxConcurrent, ignoreWaiting = true)) {
                    val stillWaiting =
                      workState.waiting.filterNot(_.sameAs(req))
                    val updatedWorkState = workState.copy(
                      grants = workState.grants
                        .acquireGrant(req.path, req.mode)
                        .updateWaiting(req.path, diff = -1),
                      waiting = stillWaiting
                    )
                    updatedWorkState -> actionAcc.appended(req.complete.void)
                  } else workState -> actionAcc
              }
            finalState -> actions.sequence_
          } else newState -> F.unit
      }

      override def readLock(path: List[String]) =
        Resource.makeFull((poll: Poll[F]) => poll(acquire(path, GrantMode.Read)))(_ =>
          release(path, GrantMode.Read)
        )

      override def writeLock(path: List[String]) =
        Resource.makeFull((poll: Poll[F]) => poll(acquire(path, GrantMode.Write)))(_ =>
          release(path, GrantMode.Write)
        )
    }
  }
}
