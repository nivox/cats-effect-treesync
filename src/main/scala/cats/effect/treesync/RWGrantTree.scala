package cats.effect.treesync

private[treesync] sealed trait GrantMode
private[treesync] object GrantMode {
  case object Read extends GrantMode
  case object Write extends GrantMode
}

private[treesync] sealed trait NodeState {
  def isWriteAcquired: Boolean = this == NodeState.AcquiredWrite
  def isReadAcquired: Boolean = this match {
    case _: NodeState.AcquiredRead => true
    case _                         => false
  }
  def notAcquired: Boolean = this == NodeState.NotAcquired

  def isAcquiredFor(mode: GrantMode): Boolean = (this, mode) match {
    case (NodeState.AcquiredWrite, GrantMode.Write)  => true
    case (_: NodeState.AcquiredRead, GrantMode.Read) => true
    case _                                           => false
  }

  def acquire(mode: GrantMode): Either[String, NodeState] = mode match {
    case GrantMode.Write => acquireWrite
    case GrantMode.Read  => acquireRead
  }

  def release(mode: GrantMode): Either[String, NodeState] = mode match {
    case GrantMode.Write => releaseWrite
    case GrantMode.Read  => releaseRead
  }

  def acquireWrite: Either[String, NodeState] = this match {
    case NodeState.NotAcquired   => Right(NodeState.AcquiredWrite)
    case NodeState.AcquiredWrite => Left("Write already acquired")
    case NodeState.AcquiredRead(_) =>
      Left("Cannot acquire write while read is acquired")
  }

  def acquireRead: Either[String, NodeState] = this match {
    case NodeState.NotAcquired => Right(NodeState.AcquiredRead(1))
    case NodeState.AcquiredWrite =>
      Left("Cannot acquire read while write is acquired")
    case NodeState.AcquiredRead(n) => Right(NodeState.AcquiredRead(n + 1))
  }

  def releaseWrite: Either[String, NodeState] = this match {
    case NodeState.AcquiredWrite => Right(NodeState.NotAcquired)
    case NodeState.NotAcquired   => Left("Write not acquired")
    case NodeState.AcquiredRead(_) =>
      Left("Cannot release write while read is acquired")
  }

  def releaseRead: Either[String, NodeState] = this match {
    case NodeState.AcquiredRead(1) => Right(NodeState.NotAcquired)
    case NodeState.AcquiredRead(n) =>
      Right(NodeState.AcquiredRead(n - 1))
    case NodeState.NotAcquired => Left("Read not acquired")
    case NodeState.AcquiredWrite =>
      Left("Cannot release read while write is acquired")
  }
}

private[treesync] object NodeState {
  case object NotAcquired extends NodeState
  case object AcquiredWrite extends NodeState
  case class AcquiredRead(n: Int) extends NodeState
}

private[treesync] object TreeState {
  val empty = TreeState(0, 0, 0)
}
private[treesync] case class TreeState(
    waiting: Long,
    acquiredWrite: Long,
    acquiredRead: Long
) {
  def hasWaiting: Boolean = waiting > 0

  def acquiredTotal: Long = acquiredWrite + acquiredRead

  def withWaitingDiff(diff: Int): TreeState = {
    val newWaiting = waiting + diff
    require(newWaiting >= 0, "Waiting count cannot be negative!")
    copy(waiting = newWaiting)
  }

  def withAcquiredGrant(mode: GrantMode): Either[String, TreeState] =
    mode match {
      case GrantMode.Write =>
        for {
          _ <- Either.cond(
            acquiredWrite == 0,
            (),
            "Cannot acquire write while write is acquired in subtree"
          )
          _ <- Either.cond(
            acquiredRead == 0,
            (),
            "Cannot acquire write while read is acquired in subtree"
          )
        } yield copy(acquiredWrite = acquiredWrite + 1)

      case GrantMode.Read =>
        for {
          _ <- Either.cond(
            acquiredWrite == 0,
            (),
            "Cannot acquire read while write is acquired in subtree"
          )
        } yield copy(acquiredRead = acquiredRead + 1)
    }

  def withAcquiredSubGrant(mode: GrantMode): TreeState = mode match {
    case GrantMode.Write =>
      copy(acquiredWrite = acquiredWrite + 1)
    case GrantMode.Read =>
      copy(acquiredRead = acquiredRead + 1)
  }

  def withReleasedGrant(mode: GrantMode): Either[String, TreeState] =
    mode match {
      case GrantMode.Write =>
        if (acquiredWrite > 0) Right(copy(acquiredWrite = acquiredWrite - 1))
        else Left("Cannot release write grant, none acquired")
      case GrantMode.Read =>
        if (acquiredRead > 0) Right(copy(acquiredRead = acquiredRead - 1))
        else Left("Cannot release read grant, none acquired")
    }
}

private[treesync] object RWGrantTree {
  val empty = RWGrantTree(
    NodeState.NotAcquired,
    TreeState.empty,
    Map.empty
  )
}
private[treesync] case class RWGrantTree(
    nodeState: NodeState,
    treeState: TreeState,
    subGrants: Map[String, RWGrantTree]
) {
  def canReclaim: Boolean =
    nodeState.notAcquired && !treeState.hasWaiting && subGrants.isEmpty

  private def canGrantImpl(
      path: List[String],
      mode: GrantMode,
      maxConcurrent: Int,
      ignoreWaiting: Boolean = false
  ): Boolean = {
    if (nodeState.isWriteAcquired) false
    else if (nodeState.isReadAcquired && mode == GrantMode.Write) false
    else if (maxConcurrent > 0 && treeState.acquiredTotal >= maxConcurrent)
      false
    else
      path.headOption match {
        case Some(head) =>
          subGrants.get(head) match {
            case Some(subGrantTree) =>
              subGrantTree.canGrantImpl(
                path.tail,
                mode,
                maxConcurrent,
                ignoreWaiting
              )
            case None =>
              true
          }

        case None =>
          if (!ignoreWaiting && treeState.waiting > 0) false
          else
            mode match {
              case GrantMode.Write =>
                treeState.acquiredTotal == 0

              case GrantMode.Read =>
                treeState.acquiredWrite == 0
            }
      }
  }

  def canGrant(
      path: List[String],
      mode: GrantMode,
      maxConcurrent: Int,
      ignoreWaiting: Boolean = false
  ): Boolean = {
    canGrantImpl(path, mode, maxConcurrent, ignoreWaiting)
  }

  def updateWaiting(path: List[String], diff: Int): RWGrantTree = {
    val updatedTreeState = treeState.withWaitingDiff(diff)
    path.headOption match {
      case Some(head) =>
        val updatedSubGrants = subGrants
          .getOrElse(head, RWGrantTree.empty)
          .updateWaiting(path.tail, diff)

        copy(
          treeState = updatedTreeState,
          subGrants =
            if (updatedSubGrants.canReclaim)
              subGrants.removed(head)
            else subGrants.updated(head, updatedSubGrants)
        )

      case None =>
        copy(treeState = updatedTreeState)
    }
  }
  
  private def acquireGrantImpl(path: List[String], mode: GrantMode, originalPath: List[String]): RWGrantTree = {
    path.headOption match {
      case Some(head) =>
        require(nodeState.notAcquired, "grant already acquired")
        val updatedTreeState = treeState.withAcquiredSubGrant(mode)
        val updatedSubGrants = subGrants.updatedWith(head) { subGrant =>
          Some(
            subGrant.getOrElse(RWGrantTree.empty).acquireGrantImpl(path.tail, mode, originalPath)
          )
        }

        copy(treeState = updatedTreeState, subGrants = updatedSubGrants)

      case None =>
        val res = for {
          updatedNodeState <- nodeState.acquire(mode)
          updatedTreeState <- treeState.withAcquiredGrant(mode)
        } yield copy(nodeState = updatedNodeState, treeState = updatedTreeState)

        res.fold(err => throw new IllegalStateException(s"Error acquiring path=${originalPath} mode=$mode: $err"), identity)
    }
  }

  def acquireGrant(path: List[String], mode: GrantMode): RWGrantTree = {
    acquireGrantImpl(path, mode, path)
  }

  private def releaseGrantImpl(
      path: List[String],
      mode: GrantMode,
      originalPath: List[String]
  ): RWGrantTree = {
    val updatedTreeState = treeState
      .withReleasedGrant(mode)
      .fold(err => throw new IllegalStateException(err), identity)
    path.headOption match {
      case Some(head) =>
        val updatedSubGrants = subGrants
          .getOrElse(
            head,
            throw new IllegalStateException(
              s"Trying to release non aquired grant: path=${originalPath}"
            )
          )
          .releaseGrantImpl(path.tail, mode, originalPath)

        copy(
          treeState = updatedTreeState,
          subGrants =
            if (updatedSubGrants.canReclaim) subGrants.removed(head)
            else subGrants.updated(head, updatedSubGrants)
        )

      case None =>
        val updatedNodeState = nodeState
          .release(mode)
          .fold(
            err =>
              throw new IllegalStateException(
                s"Trying to release grant in inconsistent state: path=${originalPath} mode=${mode} state=${nodeState}"
              ),
            identity
          )

        copy(nodeState = updatedNodeState, updatedTreeState)
    }
  }

  def releaseGrant(path: List[String], mode: GrantMode): RWGrantTree = {
    releaseGrantImpl(path, mode, path)
  }
}

