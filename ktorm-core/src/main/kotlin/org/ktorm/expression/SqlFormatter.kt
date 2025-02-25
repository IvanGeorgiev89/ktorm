/*
 * Copyright 2018-2022 the original author or authors.
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

package org.ktorm.expression

import org.ktorm.database.Database
import org.ktorm.database.DialectFeatureNotSupportedException

/**
 * Subclass of [SqlExpressionVisitor], visiting SQL expression trees using visitor pattern. After the visit completes,
 * the executable SQL string will be generated in the [sql] property with its execution parameters in [parameters].
 *
 * @property database the current database object used to obtain metadata such as identifier quote string.
 * @property beautifySql mark if we should output beautiful SQL strings with line-wrapping and indentation.
 * @property indentSize the indent size.
 * @property sql return the executable SQL string after the visit completes.
 * @property parameters return the SQL's execution parameters after the visit completes.
 */
@Suppress("VariableNaming")
public abstract class SqlFormatter(
    public val database: Database,
    public val beautifySql: Boolean,
    public val indentSize: Int
) : SqlExpressionVisitor() {

    protected var _depth: Int = 0
    protected val _builder: StringBuilder = StringBuilder()
    protected val _parameters: ArrayList<ArgumentExpression<*>> = ArrayList()

    public val sql: String get() = _builder.toString()
    public val parameters: List<ArgumentExpression<*>> get() = _parameters

    protected enum class Indentation {
        INNER, OUTER, SAME
    }

    protected fun removeLastBlank() {
        val lastIndex = _builder.lastIndex
        if (_builder[lastIndex] == ' ') {
            _builder.deleteCharAt(lastIndex)
        }
    }

    protected fun newLine(indent: Indentation) {
        when (indent) {
            Indentation.INNER -> _depth++
            Indentation.OUTER -> _depth--
            else -> { }
        }

        check(_depth >= 0) { "Incorrect indent depth: $_depth" }

        if (beautifySql) {
            _builder.appendLine()
            _builder.append(" ".repeat(_depth * indentSize))
        }
    }

    protected fun write(value: String) {
        _builder.append(value)
    }

    protected fun writeKeyword(keyword: String) {
        when (database.generateSqlInUpperCase) {
            true -> {
                _builder.append(keyword.uppercase())
            }
            false -> {
                _builder.append(keyword.lowercase())
            }
            null -> {
                if (database.supportsMixedCaseIdentifiers || !database.storesLowerCaseIdentifiers) {
                    _builder.append(keyword.uppercase())
                } else {
                    _builder.append(keyword.lowercase())
                }
            }
        }
    }

    protected open fun checkColumnName(name: String) { }

    protected open fun shouldQuote(identifier: String): Boolean {
        if (database.alwaysQuoteIdentifiers) {
            return true
        }
        if (!identifier.isIdentifier) {
            return true
        }
        if (identifier.uppercase() in database.keywords) {
            return true
        }
        if (identifier.isMixedCase
            && !database.supportsMixedCaseIdentifiers
            && database.supportsMixedCaseQuotedIdentifiers
        ) {
            return true
        }
        return false
    }

    protected val String.quoted: String get() {
        if (shouldQuote(this)) {
            if (database.supportsMixedCaseQuotedIdentifiers) {
                return "${database.identifierQuoteString}${this}${database.identifierQuoteString}"
            } else {
                if (database.storesUpperCaseQuotedIdentifiers) {
                    return "${database.identifierQuoteString}${this.uppercase()}${database.identifierQuoteString}"
                }
                if (database.storesLowerCaseQuotedIdentifiers) {
                    return "${database.identifierQuoteString}${this.lowercase()}${database.identifierQuoteString}"
                }
                if (database.storesMixedCaseQuotedIdentifiers) {
                    return "${database.identifierQuoteString}${this}${database.identifierQuoteString}"
                }
                // Should never happen, but it's still needed as some database drivers are not implemented correctly.
                return "${database.identifierQuoteString}${this}${database.identifierQuoteString}"
            }
        } else {
            if (database.supportsMixedCaseIdentifiers) {
                return this
            } else {
                if (database.storesUpperCaseIdentifiers) {
                    return this.uppercase()
                }
                if (database.storesLowerCaseIdentifiers) {
                    return this.lowercase()
                }
                if (database.storesMixedCaseIdentifiers) {
                    return this
                }
                // Should never happen, but it's still needed as some database drivers are not implemented correctly.
                return this
            }
        }
    }

    protected val String.isMixedCase: Boolean get() {
        return any { it.isUpperCase() } && any { it.isLowerCase() }
    }

    protected val String.isIdentifier: Boolean get() {
        if (this.isEmpty()) {
            return false
        } else {
            return this[0].isIdentifierStart && all { it.isIdentifierStart || it in '0'..'9' }
        }
    }

    protected val Char.isIdentifierStart: Boolean get() {
        if (this in 'a'..'z') {
            return true
        }
        if (this in 'A'..'Z') {
            return true
        }
        if (this == '_') {
            return true
        }
        if (this in database.extraNameCharacters) {
            return true
        }
        return false
    }

    protected val SqlExpression.removeBrackets: Boolean get() {
        return isLeafNode
            || this is ColumnExpression<*>
            || this is FunctionExpression<*>
            || this is AggregateExpression<*>
            || this is ExistsExpression
            || this is ColumnDeclaringExpression<*>
    }

    override fun visit(expr: SqlExpression): SqlExpression {
        val result = super.visit(expr)
        check(result === expr) { "SqlFormatter cannot modify the expression trees." }
        return result
    }

    override fun <T : SqlExpression> visitExpressionList(original: List<T>, subVisitor: (T) -> T): List<T> {
        for ((i, expr) in original.withIndex()) {
            if (i > 0) {
                removeLastBlank()
                write(", ")
            }
            subVisitor(expr)
        }
        return original
    }

    override fun <T : Any> visitArgument(expr: ArgumentExpression<T>): ArgumentExpression<T> {
        write("? ")
        _parameters += expr
        return expr
    }

    override fun <T : Any> visitUnary(expr: UnaryExpression<T>): UnaryExpression<T> {
        if (expr.type == UnaryExpressionType.IS_NULL || expr.type == UnaryExpressionType.IS_NOT_NULL) {
            if (expr.operand.removeBrackets) {
                visit(expr.operand)
            } else {
                write("(")
                visit(expr.operand)
                removeLastBlank()
                write(") ")
            }

            writeKeyword("${expr.type} ")
        } else {
            writeKeyword("${expr.type} ")

            if (expr.operand.removeBrackets) {
                visit(expr.operand)
            } else {
                write("(")
                visit(expr.operand)
                removeLastBlank()
                write(") ")
            }
        }

        return expr
    }

    override fun <T : Any> visitBinary(expr: BinaryExpression<T>): BinaryExpression<T> {
        if (expr.left.removeBrackets) {
            visit(expr.left)
        } else {
            write("(")
            visit(expr.left)
            removeLastBlank()
            write(") ")
        }

        writeKeyword("${expr.type} ")

        if (expr.right.removeBrackets) {
            visit(expr.right)
        } else {
            write("(")
            visit(expr.right)
            removeLastBlank()
            write(") ")
        }

        return expr
    }

    override fun visitTable(expr: TableExpression): TableExpression {
        if (expr.catalog != null && expr.catalog.isNotBlank()) {
            write("${expr.catalog.quoted}.")
        }
        if (expr.schema != null && expr.schema.isNotBlank()) {
            write("${expr.schema.quoted}.")
        }

        write("${expr.name.quoted} ")

        if (expr.tableAlias != null && expr.tableAlias.isNotBlank()) {
            // writeKeyword("as ")
            write("${expr.tableAlias.quoted} ")
        }

        return expr
    }

    override fun <T : Any> visitAggregate(expr: AggregateExpression<T>): AggregateExpression<T> {
        writeKeyword("${expr.type}(")
        if (expr.isDistinct) {
            writeKeyword("distinct ")
        }
        expr.argument?.let { visit(it) } ?: write("*")
        removeLastBlank()
        write(") ")
        return expr
    }

    override fun <T : Any> visitColumn(expr: ColumnExpression<T>): ColumnExpression<T> {
        if (expr.table != null) {
            if (expr.table.tableAlias != null && expr.table.tableAlias.isNotBlank()) {
                write("${expr.table.tableAlias.quoted}.")
            } else {
                if (expr.table.catalog != null && expr.table.catalog.isNotBlank()) {
                    write("${expr.table.catalog.quoted}.")
                }
                if (expr.table.schema != null && expr.table.schema.isNotBlank()) {
                    write("${expr.table.schema.quoted}.")
                }

                write("${expr.table.name.quoted}.")
            }
        }

        checkColumnName(expr.name)
        write("${expr.name.quoted} ")
        return expr
    }

    override fun <T : Any> visitColumnDeclaring(expr: ColumnDeclaringExpression<T>): ColumnDeclaringExpression<T> {
        if (expr.declaredName != null && expr.declaredName.isNotBlank()) {
            checkColumnName(expr.declaredName)
            write("${expr.declaredName.quoted} ")
        } else if (expr.expression.removeBrackets) {
            visit(expr.expression)
        } else {
            write("(")
            visit(expr.expression)
            removeLastBlank()
            write(") ")
        }

        return expr
    }

    protected fun <T : Any> visitColumnDeclaringAtSelectClause(
        expr: ColumnDeclaringExpression<T>
    ): ColumnDeclaringExpression<T> {
        visit(expr.expression)

        val column = expr.expression as? ColumnExpression<*>
        val hasDeclaredName = expr.declaredName != null && expr.declaredName.isNotBlank()

        if (hasDeclaredName && (column == null || column.name != expr.declaredName)) {
            checkColumnName(expr.declaredName!!)
            writeKeyword("as ")
            write("${expr.declaredName.quoted} ")
        }

        return expr
    }

    override fun visitOrderBy(expr: OrderByExpression): OrderByExpression {
        visit(expr.expression)
        if (expr.orderType == OrderType.DESCENDING) {
            writeKeyword("desc ")
        }
        return expr
    }

    override fun visitSelect(expr: SelectExpression): SelectExpression {
        writeKeyword("select ")
        if (expr.isDistinct) {
            writeKeyword("distinct ")
        }

        if (expr.columns.isNotEmpty()) {
            visitExpressionList(expr.columns) { visitColumnDeclaringAtSelectClause(it) }
        } else {
            write("* ")
        }

        newLine(Indentation.SAME)
        writeKeyword("from ")
        visitQuerySource(expr.from)

        if (expr.where != null) {
            newLine(Indentation.SAME)
            writeKeyword("where ")
            visit(expr.where)
        }
        if (expr.groupBy.isNotEmpty()) {
            newLine(Indentation.SAME)
            writeKeyword("group by ")
            visitGroupByList(expr.groupBy)
        }
        if (expr.having != null) {
            newLine(Indentation.SAME)
            writeKeyword("having ")
            visit(expr.having)
        }
        if (expr.orderBy.isNotEmpty()) {
            newLine(Indentation.SAME)
            writeKeyword("order by ")
            visitOrderByList(expr.orderBy)
        }
        if (expr.offset != null || expr.limit != null) {
            writePagination(expr)
        }
        return expr
    }

    override fun visitQuerySource(expr: QuerySourceExpression): QuerySourceExpression {
        when (expr) {
            is TableExpression -> {
                visitTable(expr)
            }
            is JoinExpression -> {
                visitJoin(expr)
            }
            is QueryExpression -> {
                write("(")
                newLine(Indentation.INNER)
                visitQuery(expr)
                removeLastBlank()
                newLine(Indentation.OUTER)
                write(") ")
                expr.tableAlias?.let { write("${it.quoted} ") }
            }
            else -> {
                visitUnknown(expr)
            }
        }

        return expr
    }

    override fun visitUnion(expr: UnionExpression): UnionExpression {
        when (expr.left) {
            is SelectExpression -> visitQuerySource(expr.left)
            is UnionExpression -> visitUnion(expr.left)
        }

        if (expr.isUnionAll) {
            writeKeyword("union all ")
        } else {
            writeKeyword("union ")
        }

        when (expr.right) {
            is SelectExpression -> visitQuerySource(expr.right)
            is UnionExpression -> visitUnion(expr.right)
        }

        if (expr.orderBy.isNotEmpty()) {
            newLine(Indentation.SAME)
            writeKeyword("order by ")
            visitOrderByList(expr.orderBy)
        }
        if (expr.offset != null || expr.limit != null) {
            writePagination(expr)
        }
        return expr
    }

    protected abstract fun writePagination(expr: QueryExpression)

    override fun visitJoin(expr: JoinExpression): JoinExpression {
        visitQuerySource(expr.left)
        newLine(Indentation.SAME)
        writeKeyword("${expr.type} ")
        visitQuerySource(expr.right)

        if (expr.condition != null) {
            writeKeyword("on ")
            visit(expr.condition)
        }

        return expr
    }

    override fun <T : Any> visitInList(expr: InListExpression<T>): InListExpression<T> {
        visit(expr.left)

        if (expr.notInList) {
            writeKeyword("not in ")
        } else {
            writeKeyword("in ")
        }

        if (expr.query != null) {
            visitQuerySource(expr.query)
        }
        if (expr.values != null) {
            write("(")
            visitExpressionList(expr.values)
            removeLastBlank()
            write(") ")
        }
        return expr
    }

    override fun visitExists(expr: ExistsExpression): ExistsExpression {
        if (expr.notExists) {
            writeKeyword("not exists ")
        } else {
            writeKeyword("exists ")
        }

        visitQuerySource(expr.query)

        return expr
    }

    override fun <T : Any> visitBetween(expr: BetweenExpression<T>): BetweenExpression<T> {
        visit(expr.expression)

        if (expr.notBetween) {
            writeKeyword("not between ")
        } else {
            writeKeyword("between ")
        }

        visit(expr.lower)
        writeKeyword("and ")
        visit(expr.upper)
        return expr
    }

    override fun <T : Any> visitFunction(expr: FunctionExpression<T>): FunctionExpression<T> {
        writeKeyword("${expr.functionName}(")
        visitExpressionList(expr.arguments)
        removeLastBlank()
        write(") ")
        return expr
    }

    override fun visitColumnAssignments(
        original: List<ColumnAssignmentExpression<*>>
    ): List<ColumnAssignmentExpression<*>> {
        for ((i, assignment) in original.withIndex()) {
            if (i > 0) {
                removeLastBlank()
                write(", ")
            }

            checkColumnName(assignment.column.name)
            write("${assignment.column.name.quoted} ")
            write("= ")
            visit(assignment.expression)
        }

        return original
    }

    override fun visitInsert(expr: InsertExpression): InsertExpression {
        writeKeyword("insert into ")
        visitTable(expr.table)
        writeInsertColumnNames(expr.assignments.map { it.column })
        writeKeyword("values ")
        writeInsertValues(expr.assignments)
        return expr
    }

    override fun visitInsertFromQuery(expr: InsertFromQueryExpression): InsertFromQueryExpression {
        writeKeyword("insert into ")
        visitTable(expr.table)
        writeInsertColumnNames(expr.columns)
        newLine(Indentation.SAME)
        visitQuery(expr.query)
        return expr
    }

    protected fun writeInsertColumnNames(columns: List<ColumnExpression<*>>) {
        write("(")

        for ((i, column) in columns.withIndex()) {
            if (i > 0) write(", ")
            checkColumnName(column.name)
            write(column.name.quoted)
        }

        write(") ")
    }

    protected fun writeInsertValues(assignments: List<ColumnAssignmentExpression<*>>) {
        write("(")
        visitExpressionList(assignments.map { it.expression })
        removeLastBlank()
        write(") ")
    }

    override fun visitUpdate(expr: UpdateExpression): UpdateExpression {
        writeKeyword("update ")
        visitTable(expr.table)
        writeKeyword("set ")

        visitColumnAssignments(expr.assignments)

        if (expr.where != null) {
            writeKeyword("where ")
            visit(expr.where)
        }

        return expr
    }

    override fun visitDelete(expr: DeleteExpression): DeleteExpression {
        writeKeyword("delete from ")
        visitTable(expr.table)

        if (expr.where != null) {
            writeKeyword("where ")
            visit(expr.where)
        }

        return expr
    }

    override fun visitUnknown(expr: SqlExpression): SqlExpression {
        throw DialectFeatureNotSupportedException("Unsupported expression type: ${expr.javaClass}")
    }
}
