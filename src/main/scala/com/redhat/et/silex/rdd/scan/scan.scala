/*
 * This file is part of the "silex" library of helpers for Apache Spark.
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.et.silex.rdd.scan

import scala.reflect.ClassTag

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkContext, Partition, TaskContext}
import org.apache.spark.{Dependency, NarrowDependency, OneToOneDependency}

import com.redhat.et.silex.rdd.util
import com.redhat.et.silex.rdd.cascade.implicits._

private[rdd]
class ScanPlyPartition[U: ClassTag](idx: Int, cur: (Int, Partition), prv: (Int, Partition))
  extends Partition {
  override def index = idx
  def get:((Int, Partition), (Int, Partition)) = (cur, prv)
}


private[rdd]
class ScanPlyRangeDep[U: ClassTag](rdd: RDD[U], kL: Int, kU: Int) extends NarrowDependency(rdd) {
  override def getParents(pid: Int) = if (pid >= kL  &&  pid < kU) List(pid) else Nil
}


private[rdd]
class ScanPlyOffsetDep[U: ClassTag](rdd: RDD[U], b: Int) extends NarrowDependency(rdd) {
  override def getParents(pid: Int) = if (pid >= b) List(pid - b, pid) else Nil
}


private[rdd]
class ScanPly0RDD[U: ClassTag](rdd: RDD[U]) extends RDD[U](rdd) {
  override def getPartitions = rdd.partitions.take(rdd.partitions.length - 1)
  override def compute(split: Partition, ctx: TaskContext) =
    List(rdd.iterator(split, ctx).toSeq.last).iterator
}


private[rdd]
class ScanPlyRDD[U: ClassTag](f: (U, U) => U, plies: Seq[RDD[U]]) 
  extends RDD[U](plies(0).context, Nil) {
  val ply:Array[RDD[U]] = plies.toArray
  val n = ply(0).partitions.length

  override def getPartitions = {
    val plist: ArrayBuffer[Partition] = ArrayBuffer.empty
    for (j <- 0 until ply.length) {
      val (kL, kU) = if (j <= 0) (0, 1) else (math.pow(2,j - 1).toInt, math.pow(2, j).toInt)
      for (k <- kL until kU) {
        plist += new ScanPlyPartition(k, (j, ply(j).partitions(k)), null)
      }
    }

    val jj = ply.length - 1
    val b = math.pow(2, jj).toInt

    for (k <- b until n) {
      plist += new ScanPlyPartition(k, (jj, ply(jj).partitions(k)), (jj, ply(jj).partitions(k - b)))
    }

    plist.toArray
  }

  override def getDependencies = {
    val dlist: ArrayBuffer[Dependency[U]] = ArrayBuffer.empty
    for (j <- 0 until ply.length) {
      val (kL, kU) = if (j <= 0) (0, 1) else (math.pow(2,j - 1).toInt, math.pow(2, j).toInt)
      dlist += new ScanPlyRangeDep(ply(j), kL, kU)
    }
    dlist += new ScanPlyOffsetDep(ply.last, math.pow(2, ply.length - 1).toInt)

    dlist
  }

  override def compute(split: Partition, ctx:TaskContext):Iterator[U] = {
    val p = split.asInstanceOf[ScanPlyPartition[U]]
    val (cur, prv) = p.get
    val iter = parent[U](cur._1).iterator(cur._2, ctx)
    if (prv == null) iter else {
      val x = parent[U](prv._1).iterator(prv._2, ctx).next
      List(f(x, iter.next)).iterator
    }
  }
}

private[rdd]
class ScanOutputPartition(s: Partition, o: Partition) extends Partition {
  val scan = s
  val offset = o
  override def index = scan.index
}

private[rdd]
class ScanOutputRDD[U: ClassTag](scans: RDD[U], offsets: RDD[U], f: (U, U) => U)
  extends RDD[U](scans.context, Nil) {
  override def getDependencies = {
    List(new OneToOneDependency(scans),
         new NarrowDependency(offsets) {
           override def getParents(pid: Int) = if (pid < 1) Nil else List(pid - 1)
         })
  }

  override def getPartitions = {
    Array(new ScanOutputPartition(scans.partitions.head, null)) ++
      scans.partitions.tail.zip(offsets.partitions).map(x => new ScanOutputPartition(x._1, x._2))
  }

  override def compute(split: Partition, ctx: TaskContext) = {
    val p = split.asInstanceOf[ScanOutputPartition]
    val iter = scans.iterator(p.scan, ctx)
    if (split.index == 0) iter else {
      val z = offsets.iterator(p.offset, ctx).next
      iter.drop(1).map(f(z, _))
    }
  }
}


class ScanRDDFunctions[T : ClassTag](self: RDD[T]) extends Serializable {

  /** Sequential-only prefix scan.  Analogous to scanLeft on scala sequences
    *
    * @param z The zero element for initalizing the sequential prefix scan
    * @param f maps the latest prefix scan value, plus a next element, to the next
    * prefix scan value
    * @return A new RDD containing the sequential prefix scan of the input elements.
    */
  def scanLeft[U: ClassTag](z: U)(f: (U, T) => U): RDD[U] = {
    if (self.partitions.length <= 0) return self.context.parallelize(Array(z), 1)

    val g = util.clean(
      self.context,
      (input: Iterator[T], cascade: Option[Iterator[U]]) => {
        val zz:U = cascade.map(_.toSeq.last).getOrElse(z)
        input.scanLeft(zz)(f)
      })

    self.cascadePartitions(g).mapPartitionsWithIndex((j: Int, input: Iterator[U]) => {
      if (j == 0) input else input.drop(1)
    })
  }


  /** Parallel prefix scan.  Analogous to scan on scala sequences.
    *
    * @param z The zero element to use for initializing scan (sub)sequences.  This element
    * may be used as the initial value at multiple parallel scan subsequences.
    * @param f The scanning function.  Maps the latest result of scan, plus a next element, to
    * the next prefix.
    * @return A new RDD containing the parallel prefix scan of the input elements
    */
  def scan[U >: T : ClassTag](z: U)(f: (U, U) => U): RDD[U] = {
    if (self.partitions.length <= 0) return self.context.parallelize(Array(z), 1)

    val fclean = util.clean(self.context, f)

    // Compute prefix scan on each partition
    val pps = self.mapPartitions(_.toSeq.scan(z)(fclean).iterator)

    // Extract the last row of each scan partition.  This is ply(0).
    val ply:ArrayBuffer[RDD[U]] = ArrayBuffer(new ScanPly0RDD(pps))

    // Compute the prefix scan on the last rows of the partitions to obtain
    // offsets for output partitions.  Each partition of each ply has one row.
    // There are 1+ceiling(log_base_2(n-1)) plies, where n is the number of
    // input partitions.  The total number of ply partitions is O((n)log(n)).
    var b = 1
    while (b < ply(0).partitions.length) {
      val nxt = new ScanPlyRDD(fclean, ply)
      ply += nxt
      b = 2 * b
    }

    // Add the offset for each partition (ply.last) to the per-partition scans
    new ScanOutputRDD(pps, ply.last, fclean)
  }
}

/** Implicit conversions for enhancing RDDs with scan and scanLeft methods.
  *
  *{{{
  * import com.redhat.et.silex.rdd.scan.implicits._
  *
  * rdd.scanLeft(z)(f)
  * rdd.scan(z)(g)
  *}}}
  *
  */
object implicits {
  import scala.language.implicitConversions
  implicit def rddToScanRDD[T :ClassTag](rdd: RDD[T]) = new ScanRDDFunctions(rdd)
}
