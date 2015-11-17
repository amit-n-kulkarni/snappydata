package org.apache.spark.sql.sources


import org.apache.spark.sql.types.{FloatType, DoubleType, IntegralType}

import scala.collection.mutable
import scala.util.control.Breaks._

import org.apache.spark.sql.SnappyContext
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{Subquery, LogicalPlan, UnaryNode}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{StratifiedSample}

/**
 * Created by sbhokare on 4/11/15.
 */
object ReplaceWithSampleTable extends Rule[LogicalPlan] {

  def apply(plan: LogicalPlan): LogicalPlan = {
    var errorPercent: Double = 10
    var confidence: Double = 95

    def convertToDouble(expr: Expression): Double = expr.dataType match {
      case _: IntegralType => expr.eval().asInstanceOf[Int]
      case _: DoubleType => expr.eval().asInstanceOf[Double]
      case _: FloatType => expr.eval().asInstanceOf[Float]

    }

    def traverseFilter(se: Seq[Expression], qcs: mutable.ArrayBuffer[String]): Unit = {
      for (ep <- se) {
        if (ep.isInstanceOf[AttributeReference]) {
          qcs += ep.asInstanceOf[AttributeReference].name
          //println("traverseFilter.." + qcs)
        }
        else
          traverseFilter(ep.children, qcs)
      }
    }

     plan transformDown {

      case ErrorPercent(expr, child) => {
        errorPercent = convertToDouble(expr)
        child
      } //TODO:Store confidence level some where for post-query triage
      case Confidence(expr, child) => {

        confidence = convertToDouble(expr)
        child
      } //TODO:Store confidence level some where for post-query triage
      case p@Subquery(name, child) if !child.isInstanceOf[StratifiedSample] => {
        val query_qcs = new mutable.ArrayBuffer[String]
        plan transformUp {
          case a: org.apache.spark.sql.catalyst.plans.logical.Aggregate => {
            for (ar <- a.groupingExpressions.seq) {
              query_qcs += ar.asInstanceOf[AttributeReference].name
              //println("GroupBy..." + query_qcs)
            }
            a
          }
          case f: org.apache.spark.sql.catalyst.plans.logical.Filter => {
            traverseFilter(f.condition.children, query_qcs)
            f
          }
        }

        val aqpTables = SnappyContext.SnappySC.catalog.tables.collect {
          case (sampleTableIdent, ss: StratifiedSample)
            if sampleTableIdent.table.contains(p.alias + "_") => {
            //if ss.table.equals(p.alias) => {
            (ss, sampleTableIdent.table)
          }

        }

        var aqp: (StratifiedSample, String) = null;
        var superset, subset: Seq[(StratifiedSample, String)] = Seq[(StratifiedSample,String)]()
        breakable {
          for (aqpTable: (StratifiedSample, String) <- aqpTables) {

            val table_qcs = aqpTable._1.options.get("qcs").get.toString.split(",")

            if ((query_qcs.toSet.--(table_qcs)).isEmpty) {
              if (query_qcs.size == table_qcs.size) {
                //println("table where QCS(table) == QCS(query)")
                aqp = aqpTable
                break
              }
              else {
                // println("table where QCS(table) is superset of QCS(query)")
                superset = superset.+:(aqpTable)
              }
            }
            else if ((query_qcs.toSet.--(table_qcs)).size > 0) {
              //println("table where QCS(table) is subset of QCS(query)")
              subset = subset.+:(aqpTable)
            }
          }
        }

        if (aqp == null) {
          if (superset.size > 0)
            aqp = superset(0) // Need to select one of the table based on sample size
          else if (subset.size > 0) {
            aqp = subset(0) //
          }
        }

        //println("aqpTable" + aqp)
        val newPlan = aqp match {
          case (sample, name) => ErrorAndConfidence(errorPercent, confidence, Subquery(name, sample))
          case _ => p
        }
        newPlan

      }
    }
  }
}


@transient
object TestRule extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transformDown {
    case a: org.apache.spark.sql.catalyst.plans.logical.Aggregate => {
      for (aa <- a.aggregateExpressions.seq) {
        if (aa.isInstanceOf[Alias]) {
          if (/*aa.asInstanceOf[Alias].child.isInstanceOf[WeightedAverage] ||*/
              aa.asInstanceOf[Alias].child.isInstanceOf[WeightedSum])
            //println("Run")
            ErrorEstimateAggregate(aa, 0.75, null, false, ErrorAggregate.Sum)
        }
      }
      a
    }
  }
}


case class ErrorPercent(errorPercentExpr: Expression, child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}

case class Confidence(confidenceExpr: Expression, child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}

case class ErrorAndConfidence( error: Double,  confidence: Double, child: LogicalPlan) extends  UnaryNode {
  override def output: Seq[Attribute] = child.output
}
/*case class SampleTable(child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}*/