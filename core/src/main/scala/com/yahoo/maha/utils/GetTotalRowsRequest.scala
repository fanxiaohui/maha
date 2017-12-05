package com.yahoo.maha.utils

import com.yahoo.maha.core._
import com.yahoo.maha.core.query._
import com.yahoo.maha.core.query.druid.DruidQueryGenerator
import com.yahoo.maha.core.query.oracle.OracleQueryGenerator
import com.yahoo.maha.core.registry.Registry
import com.yahoo.maha.core.request.{Field, ReportingRequest}
import grizzled.slf4j.Logging

import scala.util.Try

object GetTotalRowsRequest extends Logging {

  def getTotalRowsRequest(request: ReportingRequest, pipeline: QueryPipeline) : Try[ReportingRequest] = {
    //no filters except fk filters
    Try {
      require(
        pipeline.bestDimCandidates.nonEmpty
        , s"Invalid total rows request, no best dim candidates! : $request")

      //force dim driven
      //remove all fields except primary key
      //remove all sorts
      val primaryKeyAliasFields = pipeline.bestDimCandidates.map(dim => Field(dim.publicDim.primaryKeyByAlias, None, None)).toIndexedSeq
      request.copy(
        selectFields = primaryKeyAliasFields
        , sortBy = IndexedSeq.empty
        , includeRowCount = true
        , forceDimensionDriven = true
        , forceFactDriven = false
        , paginationStartIndex = 0
        , rowsPerPage = request.rowsPerPage
      )
    }
  }

  def getTotalRows(request: RequestModel, sourcePipeline: QueryPipeline, registry: Registry, queryContext: QueryExecutorContext)(implicit queryGeneratorRegistry: QueryGeneratorRegistry) : Try[Int] = {
    Try {
      val totalRowsRequest: ReportingRequest = getTotalRowsRequest(request.reportingRequest, sourcePipeline).get
      val model: RequestModel = RequestModel.from(totalRowsRequest, registry).get
      val maxRows: Int = DruidQueryGenerator.defaultMaximumMaxRows
      assert(model.maxRows <= maxRows, throw new Exception(s"Value of ${model.maxRows} exceeds posted limit of $maxRows"))

      val queryPipelineFactory = new DefaultQueryPipelineFactory()

      val requestPipelineTry = queryPipelineFactory.from(model, QueryAttributes.empty)
      val rowListAttempt = requestPipelineTry.toOption.get.execute(queryContext)
      assert(rowListAttempt.isSuccess, "Failed to get valid executor and row list")

      //Can fail back in getValue exception.
      var result = 0
      rowListAttempt.get._1.foreach(input => {
        assert(input.aliasMap.contains(OracleQueryGenerator.ROW_COUNT_ALIAS), "TOTALROWS not defined in alias map, only valid in Oracle Queries")
        val current_totalrows = input.aliasMap(OracleQueryGenerator.ROW_COUNT_ALIAS).toString.toInt
        logger.debug(s"Rows Returned so far: $current_totalrows")
        result += current_totalrows
      })

      result
    }
  }

}
