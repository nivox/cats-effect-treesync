package cats.effect.treesync

import scala.annotation.tailrec

private[treesync] object RWGrantCandidateTree {
  val empty: RWGrantCandidateTree = RWGrantCandidateTree(
    nodeWriteAcquired = false,
    nodeReadAcquired = false,
    treeState = TreeState.empty,
    subCandidates = Map.empty
  )

  def lazyCopyFrom(grant: RWGrantTree): RWGrantCandidateTree =
    RWGrantCandidateTree(
      grant.nodeState.isWriteAcquired,
      grant.nodeState.isReadAcquired,
      grant.treeState.copy(waiting = 0),
      Map.empty
    )
}
private[treesync] case class RWGrantCandidateTree(
    nodeWriteAcquired: Boolean,
    nodeReadAcquired: Boolean,
    treeState: TreeState,
    subCandidates: Map[String, RWGrantCandidateTree]
) {
  private def getSubCandidate(
      segment: String,
      fullPath: => List[String],
      sourceF: List[String] => Option[RWGrantTree]
  ) =
    subCandidates
      .get(segment)
      .orElse(sourceF(fullPath).map(RWGrantCandidateTree.lazyCopyFrom))

  private def canGrantImpl(
      path: List[String],
      mode: GrantMode,
      parentPath: List[String],
      sourceF: List[String] => Option[RWGrantTree]
  ): Boolean = {
    if (nodeWriteAcquired) false
    else if (nodeReadAcquired && mode == GrantMode.Write) false
    else
      path.headOption match {
        case Some(head) =>
          val fullPath = parentPath :+ head
          getSubCandidate(head, fullPath, sourceF) match {
            case Some(subCandidate) =>
              subCandidate.canGrantImpl(path.tail, mode, fullPath, sourceF)
            case None =>
              true
          }

        case None =>
          true
      }
  }

  def canGrant(
      path: List[String],
      mode: GrantMode,
      sourceF: List[String] => Option[RWGrantTree]
  ): Boolean = {
    canGrantImpl(path, mode, Nil, sourceF)
  }

  private def acquireGrantImpl(
      path: List[String],
      mode: GrantMode,
      parentPath: List[String],
      sourceF: List[String] => Option[RWGrantTree]
  ): RWGrantCandidateTree = {
    val updatedTreeState = treeState.copy(
      acquiredWrite =
        treeState.acquiredWrite + (if (mode == GrantMode.Write) 1 else 0),
      acquiredRead =
        treeState.acquiredRead + (if (mode == GrantMode.Read) 1 else 0)
    )
    path.headOption match {
      case Some(head) =>
        val fullPath = parentPath :+ head

        val updatedSubCandidates = subCandidates.updatedWith(head) { subGrant =>
          Some(
            subGrant
              .orElse(sourceF(fullPath).map(RWGrantCandidateTree.lazyCopyFrom))
              .getOrElse(RWGrantCandidateTree.empty)
              .acquireGrantImpl(path.tail, mode, fullPath, sourceF)
          )
        }

        copy(treeState = updatedTreeState, subCandidates = updatedSubCandidates)

      case None =>
        copy(
          nodeWriteAcquired = nodeWriteAcquired || mode == GrantMode.Write,
          nodeReadAcquired = nodeReadAcquired || mode == GrantMode.Read,
          treeState = updatedTreeState
        )
    }
  }

  def acquireGrant(
      path: List[String],
      mode: GrantMode,
      sourceF: List[String] => Option[RWGrantTree]
  ): RWGrantCandidateTree =
    acquireGrantImpl(path, mode, Nil, sourceF)
}

private[treesync] object RWGrantCandidateState {
  def from(grantTree: RWGrantTree): RWGrantCandidateState = {
    RWGrantCandidateState(
      RWGrantCandidateTree.lazyCopyFrom(grantTree),
      grantTree
    )
  }
}
private[treesync] case class RWGrantCandidateState(
    candidateTree: RWGrantCandidateTree,
    sourceTree: RWGrantTree
) {
  private def sourceF(path: List[String]): Option[RWGrantTree] = {
    @tailrec
    def go(rpath: List[String], grantTree: RWGrantTree): Option[RWGrantTree] =
      rpath.headOption match {
        case Some(head) =>
          grantTree.subGrants.get(head) match {
            case Some(subTree) => go(rpath.tail, subTree)
            case None          => None
          }
        case None => Some(grantTree)
      }

    go(path, sourceTree)
  }

  def canShortCircuit: Boolean = candidateTree.nodeWriteAcquired

  def evaluateCandidate(
      path: List[String],
      mode: GrantMode
  ): (RWGrantCandidateState, Boolean) = {
    val updatedCandidateTree =
      candidateTree.acquireGrant(path, mode, sourceF)

    val canGrant = candidateTree.canGrant(path, mode, sourceF)

    copy(candidateTree = updatedCandidateTree) -> canGrant
  }
}
