package com.weather.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DatabaseSchemaService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSchemaService.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * MCP Tool to get the schema (columns and types) of a specific table.
     * @param tableName The name of the table.
     * @return A string describing the table's schema, or an error message.
     */
    @Tool(name = "getTableSchema", description = "Get the schema (columns and types) of a specific PostgreSQL table.")
    public String getTableSchema(String tableName) {
        logger.debug("Getting schema for table: {}", tableName);
        String sql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position";
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, tableName);
            if (columns.isEmpty()) {
                return "Table '" + tableName + "' not found or has no columns in the public schema.";
            }
            String schema = columns.stream()
                    .map(col -> col.get("column_name") + " (" + col.get("data_type") + ")")
                    .collect(Collectors.joining("\n"));
            return "Schema for table '" + tableName + "':\n" + schema;
        } catch (Exception e) {
            logger.error("Error fetching schema for table {}: {}", tableName, e.getMessage());
            return "Error fetching schema for table '" + tableName + "': " + e.getMessage();
        }
    }

    /**
     * MCP Tool to get foreign key dependencies (constraints) for a specific table.
     * This shows which tables this table references (outgoing dependencies).
     * @param tableName The name of the table.
     * @return A string listing the foreign key constraints, or an error message.
     */
    @Tool(name = "getTableDependencies", description = "Get foreign key constraints for a specific PostgreSQL table (tables it references).")
    public String getTableDependencies(String tableName) {
        logger.debug("Getting dependencies for table: {}", tableName);
        String sql = """
            SELECT
                tc.constraint_name,
                kcu.column_name,
                ccu.table_name AS foreign_table_name,
                ccu.column_name AS foreign_column_name
            FROM
                information_schema.table_constraints AS tc
                JOIN information_schema.key_column_usage AS kcu
                  ON tc.constraint_name = kcu.constraint_name
                  AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage AS ccu
                  ON ccu.constraint_name = tc.constraint_name
                  AND ccu.table_schema = tc.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' AND tc.table_name=?;
            """;
        try {
            List<Map<String, Object>> constraints = jdbcTemplate.queryForList(sql, tableName);
            if (constraints.isEmpty()) {
                return "Table '" + tableName + "' has no outgoing foreign key dependencies in the public schema.";
            }
            String dependencies = constraints.stream()
                    .map(con -> String.format("%s (%s) -> %s (%s)",
                            tableName,
                            con.get("column_name"),
                            con.get("foreign_table_name"),
                            con.get("foreign_column_name")
                    ))
                    .collect(Collectors.joining("\n"));
            return "Dependencies for table '" + tableName + "':\n" + dependencies;
        } catch (Exception e) {
            logger.error("Error fetching dependencies for table {}: {}", tableName, e.getMessage());
            return "Error fetching dependencies for table '" + tableName + "': " + e.getMessage();
        }
    }

     /**
     * MCP Tool to get tables that have foreign key dependencies *on* a specific table.
     * This shows which tables reference the given table (incoming dependencies).
     * @param tableName The name of the table that is referenced.
     * @return A string listing the referencing tables and columns, or an error message.
     */
    @Tool(name = "getTableReferencedBy", description = "Get tables that have foreign key constraints referencing a specific PostgreSQL table (incoming dependencies).")
    public String getTableReferencedBy(String tableName) {
        logger.debug("Getting tables referencing table: {}", tableName);
        String sql = """
            SELECT
                tc.table_name AS referencing_table_name,
                kcu.column_name AS referencing_column_name,
                ccu.column_name AS referenced_column_name
            FROM
                information_schema.table_constraints AS tc
                JOIN information_schema.key_column_usage AS kcu
                  ON tc.constraint_name = kcu.constraint_name
                  AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage AS ccu
                  ON ccu.constraint_name = tc.constraint_name
                  AND ccu.table_schema = tc.table_schema
            WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_schema = 'public' AND ccu.table_name=?;
            """;
        try {
            List<Map<String, Object>> references = jdbcTemplate.queryForList(sql, tableName);
            if (references.isEmpty()) {
                return "Table '" + tableName + "' is not referenced by any foreign keys in the public schema.";
            }
            String referencingTables = references.stream()
                .map(ref -> String.format("%s (%s) references %s (%s)",
                        ref.get("referencing_table_name"),
                        ref.get("referencing_column_name"),
                        tableName,
                        ref.get("referenced_column_name")
                ))
                .collect(Collectors.joining("\n"));
            return "Tables referencing table '" + tableName + "':\n" + referencingTables;
        } catch (Exception e) {
            logger.error("Error fetching references for table {}: {}", tableName, e.getMessage());
            return "Error fetching references for table '" + tableName + "': " + e.getMessage();
        }
    }

    /**
     * MCP Tool to execute an arbitrary SQL SELECT query.
     * WARNING: Executing arbitrary SQL can be dangerous. Use with extreme caution.
     * This tool is primarily intended for SELECT statements.
     * @param sqlQuery The SQL SELECT query to execute.
     * @return A string representation of the query results, or an error message.
     */
    @Tool(name = "executeSql", description = "Executes a given SQL SELECT query against the database. WARNING: Use with extreme caution.")
    @Transactional(readOnly = true)
    public String executeSql(String sqlQuery) {
        logger.warn("Executing potentially arbitrary SQL query: {}", sqlQuery);

        // Basic sanity check - improved but still not foolproof
        if (sqlQuery == null || sqlQuery.trim().isEmpty()) {
            logger.error("executeSql attempted with empty query.");
            return "Error: SQL query cannot be empty.";
        }

        String normalizedQuery = sqlQuery.trim().toLowerCase();

        // Check if it starts with SELECT
        if (!normalizedQuery.startsWith("select")) {
            logger.error("executeSql attempted with non-SELECT query: {}", sqlQuery);
            return "Error: Only SELECT queries are allowed by this tool for safety.";
        }

        // Check for common modification keywords ( rudimentary check)
        List<String> forbiddenKeywords = List.of("insert ", "update ", "delete ", "drop ", "create ", "alter ", "truncate ", "grant ", "revoke ");
        for (String keyword : forbiddenKeywords) {
            if (normalizedQuery.contains(keyword)) {
                logger.error("executeSql attempted with potentially modifying keyword '{}': {}", keyword.trim(), sqlQuery);
                return "Error: Query contains potentially modifying keywords and is disallowed for safety.";
            }
        }

        try {
            // Use queryForList for SELECT statements
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sqlQuery);
            if (results.isEmpty()) {
                return "Query executed successfully, but returned no results.";
            }

            // Format results into a string
            StringBuilder sb = new StringBuilder("Query Results:\n");
            // Add header row
            if (!results.isEmpty()) {
                sb.append(String.join(" | ", results.get(0).keySet())).append("\n");
                sb.append("-".repeat(sb.length() - "Query Results:\n".length())).append("\n"); // Separator line
            }
            // Add data rows
            for (Map<String, Object> row : results) {
                sb.append(row.values().stream()
                           .map(val -> val != null ? val.toString() : "NULL")
                           .collect(Collectors.joining(" | "))).append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            logger.error("Error executing SQL query [{}]: {}", sqlQuery, e.getMessage(), e);
            // Provide a more informative error, but avoid leaking too much internal detail potentially
            return String.format("Error executing query: %s (Type: %s)", e.getMessage(), e.getClass().getSimpleName());
        }
    }
} 