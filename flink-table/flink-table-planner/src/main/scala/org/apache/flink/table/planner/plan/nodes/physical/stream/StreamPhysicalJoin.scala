/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.nodes.physical.stream

import org.apache.flink.table.planner.JList
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.hint.StateTtlHint
import org.apache.flink.table.planner.plan.nodes.exec.{ExecNode, InputProperty}
import org.apache.flink.table.planner.plan.nodes.exec.stream.StreamExecJoin
import org.apache.flink.table.planner.plan.nodes.physical.common.CommonPhysicalJoin
import org.apache.flink.table.planner.plan.utils.JoinUtil
import org.apache.flink.table.planner.utils.ShortcutUtils.{unwrapClassLoader, unwrapTableConfig}
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo

import org.apache.calcite.plan._
import org.apache.calcite.rel.{RelNode, RelWriter}
import org.apache.calcite.rel.core.{Join, JoinRelType}
import org.apache.calcite.rel.hint.RelHint
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.rex.RexNode

import scala.collection.JavaConversions._

/**
 * Stream physical RelNode for regular [[Join]].
 *
 * Regular joins are the most generic type of join in which any new records or changes to either
 * side of the join input are visible and are affecting the whole join result.
 */
class StreamPhysicalJoin(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    leftRel: RelNode,
    rightRel: RelNode,
    condition: RexNode,
    joinType: JoinRelType,
    hints: JList[RelHint])
  extends CommonPhysicalJoin(cluster, traitSet, leftRel, rightRel, condition, joinType, hints)
  with StreamPhysicalRel {

  /**
   * This is mainly used in `FlinkChangelogModeInferenceProgram.SatisfyUpdateKindTraitVisitor`. If
   * the unique key of input contains join key, then it can support ignoring UPDATE_BEFORE.
   * Otherwise, it can't ignore UPDATE_BEFORE. For example, if the input schema is [id, name, cnt]
   * with the unique key (id). The join key is (id, name), then an insert and update on the id:
   *
   * +I(1001, Tim, 10)
   * -U(1001, Tim, 10) +U(1001, Timo, 11)
   *
   * If the UPDATE_BEFORE is ignored, the `+I(1001, Tim, 10)` record in join will never be
   * retracted. Therefore, if we want to ignore UPDATE_BEFORE, the unique key must contain join key.
   *
   * @see
   *   FlinkChangelogModeInferenceProgram
   */
  def inputUniqueKeyContainsJoinKey(inputOrdinal: Int): Boolean = {
    val input = getInput(inputOrdinal)
    val joinKeys = if (inputOrdinal == 0) joinSpec.getLeftKeys else joinSpec.getRightKeys
    val inputUniqueKeys = getUpsertKeys(input, joinKeys)
    if (inputUniqueKeys != null) {
      inputUniqueKeys.exists(uniqueKey => joinKeys.forall(uniqueKey.contains(_)))
    } else {
      false
    }
  }

  override def requireWatermark: Boolean = false

  override def copy(
      traitSet: RelTraitSet,
      conditionExpr: RexNode,
      left: RelNode,
      right: RelNode,
      joinType: JoinRelType,
      semiJoinDone: Boolean): Join = {
    new StreamPhysicalJoin(cluster, traitSet, left, right, conditionExpr, joinType, getHints)
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    super
      .explainTerms(pw)
      .item(
        "leftInputSpec",
        JoinUtil.analyzeJoinInput(
          unwrapClassLoader(left),
          InternalTypeInfo.of(FlinkTypeFactory.toLogicalRowType(left.getRowType)),
          joinSpec.getLeftKeys,
          getUpsertKeys(left, joinSpec.getLeftKeys)
        )
      )
      .item(
        "rightInputSpec",
        JoinUtil.analyzeJoinInput(
          unwrapClassLoader(right),
          InternalTypeInfo.of(FlinkTypeFactory.toLogicalRowType(right.getRowType)),
          joinSpec.getRightKeys,
          getUpsertKeys(right, joinSpec.getRightKeys)
        )
      )
  }

  override def computeSelfCost(planner: RelOptPlanner, metadata: RelMetadataQuery): RelOptCost = {
    val elementRate = 100.0d * 2 // two input stream
    planner.getCostFactory.makeCost(elementRate, elementRate, 0)
  }

  override def translateToExecNode(): ExecNode[_] = {
    new StreamExecJoin(
      unwrapTableConfig(this),
      joinSpec,
      getUpsertKeys(left, joinSpec.getLeftKeys),
      getUpsertKeys(right, joinSpec.getRightKeys),
      InputProperty.DEFAULT,
      InputProperty.DEFAULT,
      StateTtlHint.getStateTtlFromHint(getHints),
      FlinkTypeFactory.toLogicalRowType(getRowType),
      getRelDetailedDescription)
  }
}
