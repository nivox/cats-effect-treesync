# Cats Effect Tree Sync

A hierarchical concurrency control library for Scala that builds upon cats-effect.

## Introduction

Cats Effect Tree Sync provides hierarchical synchronization primitives for Cats Effect applications.
While Cats Effect offers excellent general-purpose concurrency primitives like semaphores and mutexes, 
it lacks built-in support for hierarchical synchronization.

This library introduces specialized synchronization primitives that understand hierarchical relationships 
between resources, allowing for more efficient concurrency control in systems where resources follow a
tree-like structure (such as filesystems, document object models, or hierarchical caches).

Key improvements over standard Cats Effect synchronization:

- **Path-based locking**: Acquire locks on specific paths in a resource hierarchy
- **Fine-grained concurrency**: Independent branches can be accessed concurrently
- **Hierarchical blocking**: Block access to an entire subtree with a single lock operation
- **Read/Write separation**: Support for both shared (read) and exclusive (write) access patterns

## Installation

Add the following dependency to your `build.sbt`:

```scala
libraryDependencies += "io.github.nivox" %% "cats-effect-treesync" % "0.1.0"
```

Compatible with Scala 2.13 and Scala 3.

## TreeMutex

The `TreeMutex` allows for exclusive access to resources organized in hierarchical paths. 
This primitive ensures that:
- Only one fiber can hold a lock on a specific path or any of its ancestors/descendants at a time
- Independent paths can be accessed concurrently

### Usage Example

```scala
import cats.effect.{IO, Resource}
import cats.effect.treesync.TreeMutex

object TreeMutexExample extends IOApp.Simple {

  def run: IO[Unit] = {
    // Create a TreeMutex instance
    TreeMutex[IO]().flatMap { mutex =>
      
      // Define a function that simulates work on a resource path
      def accessResource(path: List[String], description: String): IO[Unit] = {
        mutex.lock(path).use { _ => 
          for {
            _ <- IO.println(s"Accessing $description")
            _ <- IO.sleep(1.second)
            _ <- IO.println(s"Finished with $description")
          } yield ()
        }
      }
      
      // Access resources concurrently
      val program = for {
        // These will execute concurrently (independent paths)
        fiber1 <- accessResource(List("users", "1", "profile"), "User 1 profile").start
        fiber2 <- accessResource(List("users", "2", "profile"), "User 2 profile").start
        
        // This will block until fiber1 completes (shared ancestry)
        fiber3 <- accessResource(List("users", "1"), "User 1 data").start
        
        // This will block until both fiber1 and fiber3 complete (locks the entire subtree)
        fiber4 <- accessResource(List("users"), "All users").start
        
        _ <- fiber1.join
        _ <- fiber2.join
        _ <- fiber3.join
        _ <- fiber4.join
      } yield ()
      
      program
    }
  }
}
```

## RWTreeLock

The `RWTreeLock` extends the concept of `TreeMutex` with read/write semantics, allowing:

- Multiple concurrent readers on the same path
- Exclusive write access to a path
- Fine-grained control over resource hierarchies

### Usage Example

```scala
import cats.effect.{IO, Resource}
import cats.effect.treesync.RWTreeLock

object RWTreeLockExample extends IOApp.Simple {

  def run: IO[Unit] = {
    // Create a RWTreeLock instance
    RWTreeLock[IO]().flatMap { rwLock =>
      
      // Define functions that simulate reading and writing to resources
      def readResource(path: List[String], description: String): IO[Unit] = {
        rwLock.readLock(path).use { _ => 
          for {
            _ <- IO.println(s"Reading from $description")
            _ <- IO.sleep(1.second)
            _ <- IO.println(s"Finished reading from $description")
          } yield ()
        }
      }
      
      def writeResource(path: List[String], description: String): IO[Unit] = {
        rwLock.writeLock(path).use { _ => 
          for {
            _ <- IO.println(s"Writing to $description")
            _ <- IO.sleep(1.second)
            _ <- IO.println(s"Finished writing to $description")
          } yield ()
        }
      }
      
      // Access resources with various read/write patterns
      val program = for {
        // These will execute concurrently (shared read access)
        fiber1 <- readResource(List("docs", "manual"), "manual documentation").start
        fiber2 <- readResource(List("docs", "manual"), "manual documentation (another reader)").start
        
        // This will block until both fiber1 and fiber2 complete (write blocks reads)
        fiber3 <- writeResource(List("docs", "manual"), "manual documentation").start
        
        // This can execute concurrently with fiber1 and fiber2 (independent path)
        fiber4 <- writeResource(List("docs", "api"), "API documentation").start
        
        // This will block until fiber3 and fiber4 complete (affects entire docs subtree)
        fiber5 <- writeResource(List("docs"), "all documentation").start
        
        _ <- fiber1.join
        _ <- fiber2.join
        _ <- fiber3.join
        _ <- fiber4.join
        _ <- fiber5.join
      } yield ()
      
      program
    }
  }
}
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.
