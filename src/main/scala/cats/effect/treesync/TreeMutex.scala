package cats.effect.treesync

import cats.effect.kernel._
import cats.syntax.all._

trait TreeMutex[F[_]] {
  def lock(path: List[String]): Resource[F, Unit]
}

object TreeMutex {
  def apply[F[_]](maxConcurrent: Int = 0)(implicit F: GenConcurrent[F, ?]): F[TreeMutex[F]] = {
    RWTreeLock[F](maxConcurrent).map { rwLock =>
      new TreeMutex[F] {
      def lock(path: List[String]): Resource[F, Unit] =
        rwLock.writeLock(path)
      }
    }
  }
}
