// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.jdbc

/**
 * Created by hiral on 12/19/15.
  * borrowed with modifications from :
  * https://github.com/dnvriend/scala-jdbc/blob/master/src/main/scala/com/github/dnvriend/jdbc/JdbcConnection.scala
  *
 */

import java.sql._
import javax.sql.DataSource
import scala.util.{Failure, Success, Try}
import scala.concurrent._

case class JdbcConnection(dataSource: DataSource, fetchSize: Int = 10) {

  def withConnection[R](f: Connection => R): Try[R] = blocking {
    import resource._
    managed[Connection](dataSource.getConnection)
      .map { (conn: Connection) => f(conn) }
      .either.either match {
      case Left(cause) => Failure(cause.head)
      case Right(connection) => Success(connection)
    }
  }

  def withStatement[R](f: Statement => R): Try[R] =
    withConnection[R] { (conn: Connection) => f({
        val stmt = conn.createStatement
        stmt.setFetchSize(fetchSize)
        stmt
      })
    }

  def withPreparedStatement[R](query:String)(f: PreparedStatement => R) : Try[R] =
    withConnection[R] { (conn: Connection) => f({
        val stmt = conn.prepareStatement(query)
        stmt.setFetchSize(fetchSize)
        stmt
      })
    }

  def withPreparedResultSet[R](query:String, values: Seq[Any])(implicit f: ResultSet => R): Try[R] =
    withPreparedStatement[R](query) { (preparedStatement: PreparedStatement) =>
      f(withValueInsertion(values, preparedStatement).executeQuery())
    }

  def withResultSet[R](query: String)(f: ResultSet => R): Try[R] =
    withStatement[R] { (statement: Statement) => f(statement.executeQuery(query)) }

  def queryForList[E](query: String)(implicit rowMapper: ResultSet => E): Try[Seq[E]] =
    withResultSet(query)(_.toIterator(rowMapper).toList)

  /**
   * Returns a Vector of elements
   * @param interpolation
   * @tparam A
   * @return
   */
  def queryForList[A](interpolation: SqlAndArgs)(implicit rowMapper: ResultSet => A): Try[Seq[A]] =
    queryForList(interpolation.sql, interpolation.args)

  def queryForList[E](query: String, values: Seq[Any])(implicit rowMapper: ResultSet => E): Try[Seq[E]] =
    withPreparedResultSet(query, values)(_.toIterator(rowMapper).toList)

  def queryForObject[E](query: String)(implicit rowMapper: ResultSet => E): Try[E] =
    withResultSet(query) { rs =>
      //let rowMapper call next so it knows if there is data or not
      //rs.next()
      rowMapper(rs)
    }

  /**
   * Returns an Option of an element
   * @param interpolation
   * @param rowMapper
   * @tparam A
   * @return
   */
  def queryForObject[A](interpolation: SqlAndArgs)(implicit rowMapper: ResultSet => A): Try[A] =
    queryForObject(interpolation.sql, interpolation.args)

  def queryForObject[E](query: String, values: Seq[Any])(implicit rowMapper: ResultSet => E): Try[E] =
    withPreparedResultSet(query, values) { rs =>
      //let rowMapper call next so it knows if there is data or not
      //rs.next()
      rowMapper(rs)
    }

  def mapSingle[A](interpolation: SqlAndArgs)(implicit rowMapper: ResultSet => A): Try[Option[A]] =
    queryForList(
      if(interpolation.sql.contains("LIMIT 1"))
        interpolation else interpolation.copy(sql = interpolation.sql + " LIMIT 1")
    )(rowMapper).map(_.headOption)

  def mapQuery[A](interpolation: SqlAndArgs)(implicit rowMapper: ResultSet => A): Try[Seq[A]] =
    queryForList(interpolation)

  private def withValueInsertion(values: Seq[Any], preparedStatement: PreparedStatement): PreparedStatement = {
    values.zipWithIndex.map( t => (t._1, t._2+1)).foreach {
      case (int: Int, index) => preparedStatement.setInt(index, int)
      case (long: Long, index) => preparedStatement.setLong(index, long)
      case (double: Double, index) => preparedStatement.setDouble(index, double)
      case (boolean: Boolean, index) => preparedStatement.setBoolean(index, boolean)
      case (float: Float, index) => preparedStatement.setFloat(index, float)
      case (byte: Byte, index) => preparedStatement.setByte(index, byte)
      case (short: Short, index) => preparedStatement.setShort(index, short)
      case (timestamp: Timestamp, index) => preparedStatement.setTimestamp(index, timestamp)
      case (date: Date, index) => preparedStatement.setDate(index, date)
      case (date: java.util.Date, index) => preparedStatement.setDate(index, new Date(date.getTime))
      case (any:Any, index) => preparedStatement.setString(index, any.toString)
      case (null, index) => preparedStatement.setNull(index, Types.NULL)
    }
    preparedStatement
  }

  /**
   * Executes the SQL query in this PreparedStatement object and returns the ResultSet object generated by the query.
   * This is used generally for reading the content of the database. The output will be in the form of ResultSet.
   * Generally SELECT statement is used.
   * @param interpolation
   * @return
   */
  def executeQuery(interpolation: SqlAndArgs): Try[ResultSet] =
    executeQuery(interpolation.sql, interpolation.args)

  /**
   * Executes the SQL query in this PreparedStatement object and returns the ResultSet object generated by the query.
   * This is used generally for reading the content of the database. The output will be in the form of ResultSet.
   * Generally SELECT statement is used.
   * @param query
   * @return
   */
  def executeQuery(query: String): Try[ResultSet] =
    withStatement[ResultSet] { (statement: Statement) =>
      statement.executeQuery(query)
    }

  /**
   * Executes the SQL query in this PreparedStatement object and returns the ResultSet object generated by the query.
   * This is used generally for reading the content of the database. The output will be in the form of ResultSet.
   * Generally SELECT statement is used.
   * @param query
   * @param values
   * @return
   */
  def executeQuery(query: String, values: Seq[Any]): Try[ResultSet] =
    withPreparedStatement[ResultSet](query) { preparedStatement =>
      withValueInsertion(values, preparedStatement).executeQuery()
    }

  /**
   * Executes the SQL statement in this PreparedStatement object, which must be an SQL INSERT, UPDATE or DELETE statement;
   * or an SQL statement that returns nothing, such as a DDL statement. This is generally used for altering the databases.
   * Generally DROP TABLE or DATABASE, INSERT into TABLE, UPDATE TABLE, DELETE from TABLE statements will be used in this.
   * The output will be in the form of int. This int value denotes the number of rows affected by the query.
   * @param query
   * @return
   */
  def executeUpdate(query: String): Try[Int] =
    withStatement[Int] { (statement: Statement) =>
      statement.executeUpdate(query)
    }

  /**
   * Executes the SQL statement in this PreparedStatement object, which must be an SQL INSERT, UPDATE or DELETE statement;
   * or an SQL statement that returns nothing, such as a DDL statement. This is generally used for altering the databases.
   * Generally DROP TABLE or DATABASE, INSERT into TABLE, UPDATE TABLE, DELETE from TABLE statements will be used in this.
   * The output will be in the form of int. This int value denotes the number of rows affected by the query.
   * @param interpolation
   * @return
   */
  def executeUpdate(interpolation: SqlAndArgs): Try[Int] =
    executeUpdate(interpolation.sql, interpolation.args)

  /**
   * Executes the SQL statement in this PreparedStatement object, which must be an SQL INSERT, UPDATE or DELETE statement;
   * or an SQL statement that returns nothing, such as a DDL statement. This is generally used for altering the databases.
   * Generally DROP TABLE or DATABASE, INSERT into TABLE, UPDATE TABLE, DELETE from TABLE statements will be used in this.
   * The output will be in the form of int. This int value denotes the number of rows affected by the query.
   * @param query
   * @param values
   * @return
   */
  def executeUpdate(query: String, values: Seq[Any]) : Try[Int] =
    withPreparedStatement[Int](query) { (preparedStatement: PreparedStatement) =>
      withValueInsertion(values, preparedStatement).executeUpdate()
    }

  /**
   * Executes the SQL statement in this PreparedStatement object, which may be any kind of SQL statement,
   * This will return a boolean. TRUE indicates the result is a ResultSet and FALSE indicates
   * it has the int value which denotes number of rows affected by the query. It can be used for executing stored procedures.
   * @param interpolation
   * @return
   */
  def execute(interpolation: SqlAndArgs): Try[Boolean] =
    execute(interpolation.sql, interpolation.args)

  /**
   * Executes the SQL statement in this PreparedStatement object, which may be any kind of SQL statement,
   * This will return a boolean. TRUE indicates the result is a ResultSet and FALSE indicates
   * it has the int value which denotes number of rows affected by the query. It can be used for executing stored procedures.
   * @param query
   * @param values
   * @return
   */
  def execute(query: String, values: Seq[Any]): Try[Boolean] =
    withPreparedStatement[Boolean] (query) { preparedStatement =>
      withValueInsertion(values, preparedStatement).execute()
    }

  /**
   * Executes the SQL statement in this PreparedStatement object, which may be any kind of SQL statement,
   * This will return a boolean. TRUE indicates the result is a ResultSet and FALSE indicates
   * it has the int value which denotes number of rows affected by the query. It can be used for executing stored procedures.
   * @param query
   * @return
   */
  def execute(query: String): Try[Boolean] =
    withStatement[Boolean] { statement =>
      statement.execute(query)
    }
}
