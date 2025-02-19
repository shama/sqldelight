/*
 * Copyright (C) 2017 Square, Inc.
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
package app.cash.sqldelight.core.lang.util

import app.cash.sqldelight.core.dialect.sqlite.SqliteType.INTEGER
import app.cash.sqldelight.core.dialect.sqlite.SqliteType.TEXT
import app.cash.sqldelight.core.lang.IntermediateType
import app.cash.sqldelight.core.lang.SqlDelightFile
import app.cash.sqldelight.core.lang.SqlDelightQueriesFile
import app.cash.sqldelight.core.lang.acceptsTableInterface
import app.cash.sqldelight.core.lang.psi.ColumnTypeMixin
import app.cash.sqldelight.core.lang.psi.InsertStmtValuesMixin
import com.alecstrong.sql.psi.core.DialectPreset
import com.alecstrong.sql.psi.core.psi.AliasElement
import com.alecstrong.sql.psi.core.psi.SqlColumnName
import com.alecstrong.sql.psi.core.psi.SqlCreateTableStmt
import com.alecstrong.sql.psi.core.psi.SqlCreateViewStmt
import com.alecstrong.sql.psi.core.psi.SqlCreateVirtualTableStmt
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.alecstrong.sql.psi.core.psi.SqlModuleArgument
import com.alecstrong.sql.psi.core.psi.SqlTableName
import com.alecstrong.sql.psi.core.psi.SqlTypes
import com.alecstrong.sql.psi.core.psi.mixins.ColumnDefMixin
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.jgrapht.Graph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.DirectedAcyclicGraph
import org.jgrapht.traverse.TopologicalOrderIterator

internal inline fun <reified R : PsiElement> PsiElement.parentOfType(): R {
  return PsiTreeUtil.getParentOfType(this, R::class.java)!!
}

internal fun PsiElement.type(): IntermediateType = when (this) {
  is AliasElement -> source().type().copy(name = name)
  is ColumnDefMixin -> (columnType as ColumnTypeMixin).type()
  is SqlColumnName -> {
    when (val parentRule = parent) {
      is ColumnDefMixin -> parentRule.type()
      is SqlCreateVirtualTableStmt -> IntermediateType(TEXT, name = this.name)
      else -> {
        when (val resolvedReference = reference!!.resolve()!!) {
          // Synthesized columns refer directly to the table
          is SqlCreateTableStmt,
          is SqlCreateVirtualTableStmt -> synthesizedColumnType(this.name)
          else -> {
            val columnSelected = queryAvailable(this).flatMap { it.columns }
              .firstOrNull { it.element == resolvedReference }
            columnSelected?.nullable?.let {
              resolvedReference.type().nullableIf(it)
            } ?: resolvedReference.type()
          }
        }
      }
    }
  }
  is SqlExpr -> type()
  else -> throw IllegalStateException("Cannot get function type for psi type ${this.javaClass}")
}

private fun synthesizedColumnType(columnName: String): IntermediateType {
  val sqliteType = when (columnName) {
    "docid", "rowid", "oid", "_rowid_" -> INTEGER
    else -> TEXT
  }

  return IntermediateType(sqliteType, name = columnName)
}

internal fun PsiElement.sqFile(): SqlDelightFile = containingFile as SqlDelightFile

inline fun <reified T : PsiElement> PsiElement.findChildrenOfType(): Collection<T> {
  return PsiTreeUtil.findChildrenOfType(this, T::class.java)
}

inline fun <reified T : PsiElement> PsiElement.findChildOfType(): T? {
  return PsiTreeUtil.findChildOfType(this, T::class.java)
}

fun PsiElement.childOfType(type: IElementType): PsiElement? {
  return node.findChildByType(type)?.psi
}

fun PsiElement.childOfType(types: TokenSet): PsiElement? {
  return node.findChildByType(types)?.psi
}

inline fun <reified T : PsiElement> PsiElement.nextSiblingOfType(): T {
  return PsiTreeUtil.getNextSiblingOfType(this, T::class.java)!!
}

private fun PsiElement.rangesToReplace(): List<Pair<IntRange, String>> {
  return if (this is ColumnTypeMixin && javaTypeName != null) {
    listOf(
      Pair(
        first = (typeName.node.startOffset + typeName.node.textLength) until
          (javaTypeName!!.node.startOffset + javaTypeName!!.node.textLength),
        second = ""
      )
    )
  } else if (this is SqlModuleArgument && moduleArgumentDef?.columnDef != null && (parent as SqlCreateVirtualTableStmt).moduleName?.text?.toLowerCase() == "fts5") {
    val columnDef = moduleArgumentDef!!.columnDef!!
    // If there is a space at the end of the constraints, preserve it.
    val lengthModifier = if (columnDef.columnConstraintList.isNotEmpty() && columnDef.columnConstraintList.last()?.lastChild?.prevSibling is PsiWhiteSpace) 1 else 0
    listOf(
      Pair(
        first = (columnDef.columnName.node.startOffset + columnDef.columnName.node.textLength) until
          (columnDef.columnName.node.startOffset + columnDef.node.textLength - lengthModifier),
        second = ""
      )
    )
  } else if (this is InsertStmtValuesMixin && parent?.acceptsTableInterface() == true) {
    listOf(
      Pair(
        first = childOfType(SqlTypes.BIND_EXPR)!!.range,
        second = parent!!.columns.joinToString(separator = ", ", prefix = "(", postfix = ")") { "?" }
      )
    )
  } else {
    children.flatMap { it.rangesToReplace() }
  }
}

private operator fun IntRange.minus(amount: Int): IntRange {
  return IntRange(start - amount, endInclusive - amount)
}

private val IntRange.length: Int
  get() = endInclusive - start + 1

fun PsiElement.rawSqlText(
  replacements: List<Pair<IntRange, String>> = emptyList()
): String {
  return (replacements + rangesToReplace())
    .sortedBy { it.first.first }
    .map { (range, replacement) -> (range - node.startOffset) to replacement }
    .fold(
      0 to text,
      { (totalRemoved, sqlText), (range, replacement) ->
        (totalRemoved + (range.length - replacement.length)) to sqlText.replaceRange(range - totalRemoved, replacement)
      }
    ).second
}

internal val PsiElement.range: IntRange
  get() = node.startOffset until (node.startOffset + node.textLength)

fun Collection<SqlDelightQueriesFile>.forInitializationStatements(
  allowReferenceCycles: Boolean,
  body: (sqlText: String) -> Unit
) {
  val views = ArrayList<SqlCreateViewStmt>()
  val tables = ArrayList<SqlCreateTableStmt>()
  val creators = ArrayList<PsiElement>()
  val miscellanious = ArrayList<PsiElement>()

  forEach { file ->
    file.sqliteStatements()
      .filter { (label, _) -> label.name == null }
      .forEach { (_, sqliteStatement) ->
        when {
          sqliteStatement.createTableStmt != null -> tables.add(sqliteStatement.createTableStmt!!)
          sqliteStatement.createViewStmt != null -> views.add(sqliteStatement.createViewStmt!!)
          sqliteStatement.createTriggerStmt != null -> creators.add(sqliteStatement.createTriggerStmt!!)
          sqliteStatement.createIndexStmt != null -> creators.add(sqliteStatement.createIndexStmt!!)
          else -> miscellanious.add(sqliteStatement)
        }
      }
  }

  when (allowReferenceCycles) {
    // If we allow cycles, don't attempt to order the table creation statements. The dialect
    // is permissive.
    true -> tables.forEach { body(it.rawSqlText()) }
    false -> tables.buildGraph().topological().forEach { body(it.rawSqlText()) }
  }

  views.orderStatements(
    { it.viewName.name },
    { view: SqlCreateViewStmt -> view.compoundSelectStmt!!.findChildrenOfType<SqlTableName>() },
    { it.name },
    body,
  )

  creators.forEach { body(it.rawSqlText()) }
  miscellanious.forEach { body(it.rawSqlText()) }
}

private fun ArrayList<SqlCreateTableStmt>.buildGraph(): Graph<SqlCreateTableStmt, DefaultEdge> {
  val graph = DirectedAcyclicGraph<SqlCreateTableStmt, DefaultEdge>(DefaultEdge::class.java)
  val namedStatements = this.associateBy { it.tableName.name }

  this.forEach { table ->
    graph.addVertex(table)
    table.columnDefList.forEach { column ->
      column.columnConstraintList.mapNotNull { it.foreignKeyClause?.foreignTable }.forEach { fk ->
        try {
          val foreignTable = namedStatements[fk.name]
          graph.apply {
            addVertex(foreignTable)
            addEdge(foreignTable, table)
          }
        } catch (e: IllegalArgumentException) {
          error("Detected cycle between ${table.tableName.name}.${column.columnName.name} and ${fk.name}. Consider lifting the foreign key constraint out of the table definition.")
        }
      }
    }
  }

  return graph
}

private fun <V, E> Graph<V, E>.topological(): Iterator<V> = TopologicalOrderIterator(this)

val DialectPreset.allowsReferenceCycles get() = when (this) {
  DialectPreset.SQLITE_3_18, DialectPreset.SQLITE_3_24, DialectPreset.SQLITE_3_25 -> true
  DialectPreset.MYSQL -> true
  DialectPreset.POSTGRESQL -> false
  DialectPreset.HSQL -> true
}

private fun <T : PsiElement, E : PsiElement> ArrayList<T>.orderStatements(
  nameSelector: (T) -> String,
  relationIdentifier: (T) -> Collection<E>,
  relatedNameSelector: (E) -> String,
  body: (sqlText: String) -> Unit,
) {
  val statementsLeft = this.map(nameSelector).toMutableSet()
  while (this.isNotEmpty()) {
    this.removeAll { statement ->
      val relatedStatements = relationIdentifier(statement)
      if (relatedStatements.any { relatedNameSelector(it) in statementsLeft }) {
        return@removeAll false
      }

      body(statement.rawSqlText())
      statementsLeft.remove(nameSelector(statement))
      return@removeAll true
    }
  }
}
