package cats.effect.treesync

import cats.effect.IO
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import scala.concurrent.duration._
import cats.effect.kernel.Fiber
import cats.implicits._
import cats.effect.kernel.Outcome
import cats.effect.kernel.Deferred

class RWTreeLockSuite extends CatsEffectSuite {

  def runTest[T](f: RWTreeLock[IO] => IO[T]) =
    TestControl.executeEmbed {
      for {
        m   <- RWTreeLock[IO]()
        res <- f(m)
      } yield res
    }

  def joinAll(fibers: Fiber[IO, Throwable, _]*): IO[Unit] =
    fibers.toList.traverse { f =>
      f.join.flatMap {
        case Outcome.Succeeded(_) => IO.unit
        case Outcome.Errored(err) => IO.raiseError(err)
        case Outcome.Canceled()   => IO.raiseError(new IllegalArgumentException("Cancelled"))
      }
    }.void

  test("prevents concurrent write access on root path") {
    runTest { m =>
      for {
        gate1 <- Deferred[IO, Unit]
        gate2 <- Deferred[IO, Unit]

        fiber1 <- m.writeLock(Nil).surround(gate1.complete(()) >> gate2.get).start
        fiber2 <- m.writeLock(Nil).surround(gate1.get >> gate2.complete(())).start
        _      <- joinAll(fiber1, fiber2)
      } yield ()
    }.intercept[TestControl.NonTerminationException]
  }

  test("allows concurrent read access on root path") {
    runTest { m =>
      for {
        gate1 <- Deferred[IO, Unit]
        gate2 <- Deferred[IO, Unit]

        fiber1 <- m.readLock(Nil).surround(gate1.complete(()) >> gate2.get).start
        fiber2 <- m.readLock(Nil).surround(gate1.get >> gate2.complete(())).start
        _      <- joinAll(fiber1, fiber2)
      } yield ()
    }
  }

  test("prevents concurrent write access on same sub paths") {
    runTest { m =>
      for {
        gate1 <- Deferred[IO, Unit]
        gate2 <- Deferred[IO, Unit]

        fiber1 <- m.writeLock(List("A")).surround(gate1.complete(()) >> gate2.get).start
        fiber2 <- m.writeLock(List("A")).surround(gate1.get >> gate2.complete(())).start
        _      <- joinAll(fiber1, fiber2)
      } yield ()
    }.intercept[TestControl.NonTerminationException]
  }

  test("allows concurrent read access on same sub paths") {
    runTest { m =>
      for {
        gate1 <- Deferred[IO, Unit]
        gate2 <- Deferred[IO, Unit]

        fiber1 <- m.readLock(List("A")).surround(gate1.complete(()) >> gate2.get).start
        fiber2 <- m.readLock(List("A")).surround(gate1.get >> gate2.complete(())).start
        _      <- joinAll(fiber1, fiber2)
      } yield ()
    }
  }

  test("allows concurrent write access on independent sub paths") {
    runTest { m =>
      for {
        gate1 <- Deferred[IO, Unit]
        gate2 <- Deferred[IO, Unit]

        fiber1 <- m.writeLock(List("A")).surround(gate1.complete(()) >> gate2.get).start
        fiber2 <- m.writeLock(List("B")).surround(gate1.get >> gate2.complete(())).start
        _      <- joinAll(fiber1, fiber2)
      } yield ()
    }.assertEquals(())
  }

  test("queues write access on dependent sub paths") {
    runTest { m =>
      for {
        acc <- IO.ref(List[String]())

        _ <- m.acquire(List("A"), GrantMode.Write)
        fiber1 <- m.writeLock(List("A", "1"))
                    .surround {
                      acc.update(_ :+ "A1")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber2 <- m.writeLock(List("A", "2"))
                    .surround {
                      acc.update(_ :+ "A2")
                    }
                    .start

        _ <- IO.sleep(1.second)
        _ <- acc.update(_ :+ "A")

        _ <- m.release(List("A"), GrantMode.Write)
        _ <- joinAll(fiber1, fiber2)

        result <- acc.get
      } yield result
    }.map { res =>
      // A must be the first one
      assertEquals(res.head, "A")
      // A1 and A2 can be in any order since we don't know how they are scheduled when woken up
      assertEquals(res.tail.toSet, Set("A1", "A2"))

      ()
    }.assertEquals(())
  }

  test("queues mixed write/read access on dependent sub paths") {
    runTest { m =>
      for {
        acc <- IO.ref(List[String]())

        _ <- m.acquire(List("A"), GrantMode.Write)
        fiber1 <- m.readLock(List("A", "1"))
                    .surround {
                      acc.update(_ :+ "A1")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber2 <- m.readLock(List("A", "2"))
                    .surround {
                      acc.update(_ :+ "A2")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber3 <- m.writeLock(List("A"))
          .surround {
            acc.update(_ :+ "A")
          }.start

        _      <- IO.sleep(1.second)
        fiber4 <- m.readLock(List("A", "4"))
                    .surround {
                      acc.update(_ :+ "A4")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber5 <- m.readLock(List("A", "5"))
                    .surround {
                      acc.update(_ :+ "A5")
                    }
                    .start

        _ <- IO.sleep(1.second)
        _ <- acc.update(_ :+ "A")

        _ <- m.release(List("A"), GrantMode.Write)
        _ <- joinAll(fiber1, fiber2, fiber3, fiber4, fiber5)

        result <- acc.get
      } yield result
    }.map { res =>
      // A must be the first one
      assertEquals(res.head, "A")
      // A1 and A2 must follow since they are blocked by A
      assertEquals(res.slice(1,3).toSet, Set("A1", "A2"))

      // A is blocked by A1 and A2 so it must wait
      assertEquals(res(3), "A")
      // A4 and A5 must wait for A since write cannot happen concurrently to reads
      assertEquals(res.slice(4,6).toSet, Set("A4", "A5"))

      ()
    }.assertEquals(())
  }

  test("allow concurrent access to independent sub paths also when there are fibers waiting") {
    runTest { m =>
      for {
        acc <- IO.ref(List[String]())

        _ <- m.acquire(List("A"), GrantMode.Write)
        _      <- acc.update(_ :+ "A")

        fiber1 <- m.writeLock(List("A", "1"))
                    .surround {
                      acc.update(_ :+ "A1")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber2 <- m.writeLock(List("A", "2"))
                    .surround {
                      acc.update(_ :+ "A2")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber3 <- m.writeLock(List("B"))
                    .surround {
                      acc.update(_ :+ "B")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber4 <- m.readLock(List("C"))
                    .surround {
                      acc.update(_ :+ "C")
                    }
                    .start

        _ <- IO.sleep(1.second)
        _ <- m.release(List("A"), GrantMode.Write)
        _ <- joinAll(fiber1, fiber2, fiber3, fiber4)

        result <- acc.get
      } yield result
    }.map { res =>
      // A must be the first one
      assertEquals(res(0), "A")
      // B must be the second since it's non a independent path
      assertEquals(res.slice(1, 3).toSet, Set("B", "C"))
      // A1 and A2 can be in any order since we don't know how they are scheduled when woken up
      assertEquals(res.drop(3).toSet, Set("A1", "A2"))
    }.assertEquals(())
  }

  test("unblock all queued fibers that can run independently at the same time considering fairness") {
    runTest { m =>
      for {
        acc <- IO.ref(List[String]())

        _ <- m.acquire(List("A"), GrantMode.Write)
        _      <- acc.update(_ :+ "A")

        fiber1 <- m.writeLock(List("A", "1"))
                    .surround {
                      acc.update(_ :+ "A1")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber2 <- m.readLock(List("A", "2"))
                    .surround {
                      acc.update(_ :+ "A2")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber3 <- m.writeLock(List("A", "3"))
                    .surround {
                      acc.update(_ :+ "A3")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber4 <- m.writeLock(List("A", "4"))
                    .surround {
                      acc.update(_ :+ "A4")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber5 <- m.writeLock(List("A"))
                    .surround {
                      acc.update(_ :+ "A")
                    }
                    .start

        _      <- IO.sleep(1.second)
        fiber6 <- m.writeLock(List("A", "5"))
                    .surround {
                      acc.update(_ :+ "A5")
                    }
                    .start

        _ <- IO.sleep(1.second)
        _ <- m.release(List("A"), GrantMode.Write)
        _ <- joinAll(fiber1, fiber2, fiber3, fiber4, fiber5, fiber6)

        result <- acc.get
      } yield result
    }.map { res =>
      // A must be the first one
      assertEquals(res.head, "A")
      // A1..A4 can be granted immediately
      assertEquals(res.slice(1, 5).toSet, Set("A1", "A2", "A3", "A4"))
      // A blocks A5, since it is before in the waiting queue
      assertEquals(res(5), "A")
      // A5 comes last, ensuring fairness to A
      assertEquals(res(6), "A5")
    }
  }
}
