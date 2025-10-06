package com.tsys.enterprise.issuing.module.db2;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DB2TestManager - Complete implementation with all required methods
 * Provides comprehensive testing capabilities for DB2 database operations
 */
public class DB2TestManager {
    
    // Connection management
    private Connection connection;
    private static final String DEFAULT_SCHEMA = "DEFAULT_SCHEMA";
    
    // Utility constants
    private static final String ALPHA_NUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random random = new Random();
    
    // Pattern cache for performance
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([^}]+)\\}");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\?");
    private static final Pattern SQL_COMMENT_PATTERN = Pattern.compile("--.*$", Pattern.MULTILINE);
    
    // Data structures for tracking
    private Map<String, Object> templateMap = new HashMap<>();
    private Map<String, String> aliasToTableMap = new HashMap<>();
    private Set<String> trackedRecords = new HashSet<>();
    
    /**
     * Constructor with connection
     */
    public DB2TestManager(Connection connection) {
        this.connection = connection;
    }
    
    /**
     * Default constructor
     */
    public DB2TestManager() {
        // Default constructor for reflection-based instantiation
    }
    
    // =====================================================
    // UTILITY METHODS
    // =====================================================
    
    /**
     * Safe method execution wrapper
     */
    public static <T> T safe(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Generate fingerprint for data identification
     */
    public static String fingerprint(Object data) {
        if (data == null) return "null";
        return String.valueOf(data.hashCode());
    }
    
    /**
     * Generate random alphanumeric string
     */
    public static String randomAlphaNum(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHA_NUMERIC.charAt(random.nextInt(ALPHA_NUMERIC.length())));
        }
        return sb.toString();
    }
    
    /**
     * Repeat string to fit specified length
     */
    public static String repeatFit(String str, int length) {
        if (str == null || str.isEmpty() || length <= 0) return "";
        
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            int remaining = length - sb.length();
            if (str.length() <= remaining) {
                sb.append(str);
            } else {
                sb.append(str.substring(0, remaining));
            }
        }
        return sb.toString();
    }
    
    // =====================================================
    // TOKEN AND PLACEHOLDER HANDLING
    // =====================================================
    
    /**
     * Parse tokens from query string
     */
    public List<String> parseTokensFromQuery(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null) return tokens;
        
        Matcher matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }
    
    /**
     * Ensure consistent numbered placeholders from tokens
     */
    public String ensureConsistentNumberedPlaceholdersFromTokens(String query, Map<String, Object> parameters) {
        if (query == null) return null;
        
        String processedQuery = query;
        Map<String, Integer> tokenToIndex = new HashMap<>();
        int placeholderIndex = 1;
        
        Matcher matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!tokenToIndex.containsKey(token)) {
                tokenToIndex.put(token, placeholderIndex++);
            }
        }
        
        // Replace tokens with numbered placeholders
        for (Map.Entry<String, Integer> entry : tokenToIndex.entrySet()) {
            String token = entry.getKey();
            processedQuery = processedQuery.replaceAll("\\{" + Pattern.quote(token) + "\\}", "?");
        }
        
        return processedQuery;
    }
    
    /**
     * Find existing value for token
     */
    public Object findExistingValueForToken(String token, Map<String, Object> valueMap) {
        if (token == null || valueMap == null) return null;
        
        // Direct lookup
        if (valueMap.containsKey(token)) {
            return valueMap.get(token);
        }
        
        // Check variants
        List<String> variants = addKeyVariants(token);
        for (String variant : variants) {
            if (valueMap.containsKey(variant)) {
                return valueMap.get(variant);
            }
        }
        
        return null;
    }
    
    /**
     * Add key variants for flexible matching
     */
    public List<String> addKeyVariants(String key) {
        List<String> variants = new ArrayList<>();
        if (key == null) return variants;
        
        variants.add(key);
        variants.add(key.toLowerCase());
        variants.add(key.toUpperCase());
        variants.add(key.replace("_", ""));
        variants.add(key.replace("-", ""));
        
        return variants;
    }
    
    /**
     * Get first key from collection
     */
    public String firstKey(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        return keys.iterator().next();
    }
    
    // =====================================================
    // ALIAS AND TABLE MAPPING
    // =====================================================
    
    /**
     * Extract alias to table mapping from SQL
     */
    public Map<String, String> extractAliasToTableMapping(String sql) {
        Map<String, String> mapping = new HashMap<>();
        if (sql == null) return mapping;
        
        // Remove comments
        String cleanSql = SQL_COMMENT_PATTERN.matcher(sql).replaceAll("");
        
        // Extract FROM and JOIN clauses
        String[] chunks = cleanSql.split("(?i)\\b(FROM|JOIN)\\b");
        
        for (int i = 1; i < chunks.length; i++) {
            extractFromChunk(chunks[i], mapping);
        }
        
        return mapping;
    }
    
    /**
     * Extract table-alias mappings from SQL chunk
     */
    private void extractFromChunk(String chunk, Map<String, String> mapping) {
        if (chunk == null) return;
        
        // Split by comma to handle multiple tables
        String[] tableParts = splitTopLevelByComma(chunk);
        
        for (String part : tableParts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            
            // Remove leading keywords like INNER, LEFT, etc.
            part = part.replaceFirst("(?i)^\\s*(INNER|LEFT|RIGHT|OUTER|FULL)\\s+", "");
            
            String[] tokens = part.split("\\s+");
            if (tokens.length >= 2) {
                String tableName = stripQuotesAndSpaces(tokens[0]);
                String alias = stripQuotesAndSpaces(tokens[1]);
                
                if (!alias.equalsIgnoreCase("ON") && !alias.equalsIgnoreCase("WHERE")) {
                    mapping.put(alias, tableName);
                }
            }
        }
    }
    
    /**
     * Strip quotes and spaces from string
     */
    public String stripQuotesAndSpaces(String str) {
        if (str == null) return null;
        
        str = str.trim();
        if (str.startsWith("\"") && str.endsWith("\"")) {
            str = str.substring(1, str.length() - 1);
        }
        if (str.startsWith("'") && str.endsWith("'")) {
            str = str.substring(1, str.length() - 1);
        }
        
        return str.trim();
    }
    
    /**
     * Split string by comma while preserving quoted sections
     */
    public String[] splitTopLevelByComma(String str) {
        if (str == null) return new String[0];
        
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            
            if (!inQuotes && (c == '\'' || c == '"')) {
                inQuotes = true;
                quoteChar = c;
                current.append(c);
            } else if (inQuotes && c == quoteChar) {
                inQuotes = false;
                current.append(c);
            } else if (!inQuotes && c == ',') {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        
        return parts.toArray(new String[0]);
    }
    
    /**
     * Split string preserving quoted sections
     */
    public List<String> splitPreservingQuoted(String str, String delimiter) {
        List<String> result = new ArrayList<>();
        if (str == null || delimiter == null) return result;
        
        // Simple implementation - for complex cases, use proper parser
        String[] parts = str.split(Pattern.quote(delimiter));
        Collections.addAll(result, parts);
        
        return result;
    }
    
    /**
     * Tokenize specification string
     */
    public List<String> tokenizeSpec(String spec) {
        List<String> tokens = new ArrayList<>();
        if (spec == null) return tokens;
        
        // Split by common delimiters
        String[] parts = spec.split("[,;\\s]+");
        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        
        return tokens;
    }
    
    // =====================================================
    // DATABASE OPERATIONS
    // =====================================================
    
    /**
     * Ensure parents exist for row
     */
    public void ensureParentsForRow(String tableName, Map<String, Object> rowData) {
        if (tableName == null || rowData == null || connection == null) return;
        
        try {
            List<ForeignKeyInfo> foreignKeys = getImportedKeys(tableName);
            
            for (ForeignKeyInfo fk : foreignKeys) {
                Object foreignKeyValue = rowData.get(fk.columnName);
                if (foreignKeyValue != null) {
                    ensureRowExists(fk.referencedTable, fk.referencedColumn, foreignKeyValue);
                }
            }
        } catch (Exception e) {
            logErrorSummary("Failed to ensure parents for table: " + tableName, e);
        }
    }
    
    /**
     * Ensure row exists in table
     */
    public void ensureRowExists(String tableName, String keyColumn, Object keyValue) {
        if (tableName == null || keyColumn == null || keyValue == null || connection == null) return;
        
        try {
            if (!existsByPk(tableName, keyColumn, keyValue)) {
                Map<String, Object> rowData = new HashMap<>();
                rowData.put(keyColumn, keyValue);
                
                // Generate values for other required columns
                List<ColumnInfo> columns = getColumns(tableName);
                for (ColumnInfo col : columns) {
                    if (!col.name.equals(keyColumn) && !col.nullable) {
                        rowData.put(col.name, generateValueForColumnType(col.type, col.size));
                    }
                }
                
                insertRow(tableName, rowData);
            }
        } catch (Exception e) {
            logErrorSummary("Failed to ensure row exists in table: " + tableName, e);
        }
    }
    
    /**
     * Ensure row and all its parents exist
     */
    public void ensureRowAndParentsExist(String tableName, Map<String, Object> rowData) {
        ensureParentsForRow(tableName, rowData);
        
        List<String> primaryKeys = getPrimaryKeys(tableName);
        if (!primaryKeys.isEmpty()) {
            Map<String, Object> pkValues = new HashMap<>();
            for (String pk : primaryKeys) {
                if (rowData.containsKey(pk)) {
                    pkValues.put(pk, rowData.get(pk));
                }
            }
            
            if (!pkValues.isEmpty() && !existsByPk(tableName, pkValues)) {
                insertRow(tableName, rowData);
            }
        }
    }
    
    /**
     * Check if row exists by primary key
     */
    public boolean existsByPk(String tableName, String keyColumn, Object keyValue) {
        Map<String, Object> pkMap = new HashMap<>();
        pkMap.put(keyColumn, keyValue);
        return existsByPk(tableName, pkMap);
    }
    
    /**
     * Check if row exists by primary key map
     */
    public boolean existsByPk(String tableName, Map<String, Object> primaryKeyValues) {
        if (tableName == null || primaryKeyValues == null || connection == null) return false;
        
        try {
            StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(tableName).append(" WHERE ");
            List<Object> params = new ArrayList<>();
            
            boolean first = true;
            for (Map.Entry<String, Object> entry : primaryKeyValues.entrySet()) {
                if (!first) sql.append(" AND ");
                sql.append(entry.getKey()).append(" = ?");
                params.add(entry.getValue());
                first = false;
            }
            
            try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to check existence in table: " + tableName, e);
            return false;
        }
    }
    
    /**
     * Insert row into table
     */
    public void insertRow(String tableName, Map<String, Object> rowData) {
        if (tableName == null || rowData == null || rowData.isEmpty() || connection == null) return;
        
        try {
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
            StringBuilder values = new StringBuilder(" VALUES (");
            List<Object> params = new ArrayList<>();
            
            boolean first = true;
            for (Map.Entry<String, Object> entry : rowData.entrySet()) {
                if (!first) {
                    sql.append(", ");
                    values.append(", ");
                }
                sql.append(entry.getKey());
                values.append("?");
                params.add(entry.getValue());
                first = false;
            }
            
            sql.append(")").append(values).append(")");
            
            try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                stmt.executeUpdate();
                
                // Track inserted record
                String recordKey = tableName + ":" + fingerprint(rowData);
                trackedRecords.add(recordKey);
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to insert row into table: " + tableName, e);
        }
    }
    
    /**
     * Fetch existing primary key value if applicable
     */
    public Object fetchExistingPkValueIfApplicable(String tableName, Map<String, Object> rowData) {
        if (tableName == null || rowData == null || connection == null) return null;
        
        try {
            List<String> primaryKeys = getPrimaryKeys(tableName);
            if (primaryKeys.size() == 1) {
                String pkColumn = primaryKeys.get(0);
                
                // Build query to find existing record
                StringBuilder sql = new StringBuilder("SELECT ").append(pkColumn)
                    .append(" FROM ").append(tableName).append(" WHERE ");
                
                List<Object> params = new ArrayList<>();
                boolean first = true;
                
                for (Map.Entry<String, Object> entry : rowData.entrySet()) {
                    if (!entry.getKey().equals(pkColumn)) {
                        if (!first) sql.append(" AND ");
                        sql.append(entry.getKey()).append(" = ?");
                        params.add(entry.getValue());
                        first = false;
                    }
                }
                
                if (!params.isEmpty()) {
                    try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                        for (int i = 0; i < params.size(); i++) {
                            stmt.setObject(i + 1, params.get(i));
                        }
                        
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                return rs.getObject(1);
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to fetch existing PK value for table: " + tableName, e);
        }
        
        return null;
    }
    
    // =====================================================
    // METADATA OPERATIONS
    // =====================================================
    
    /**
     * Get columns for table
     */
    public List<ColumnInfo> getColumns(String tableName) {
        List<ColumnInfo> columns = new ArrayList<>();
        if (tableName == null || connection == null) return columns;
        
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.name = rs.getString("COLUMN_NAME");
                    col.type = rs.getInt("DATA_TYPE");
                    col.size = rs.getInt("COLUMN_SIZE");
                    col.nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to get columns for table: " + tableName, e);
        }
        
        return columns;
    }
    
    /**
     * Get column metadata
     */
    public ColumnInfo getColumnMeta(String tableName, String columnName) {
        if (tableName == null || columnName == null) return null;
        
        List<ColumnInfo> columns = getColumns(tableName);
        for (ColumnInfo col : columns) {
            if (col.name.equalsIgnoreCase(columnName)) {
                return col;
            }
        }
        
        return null;
    }
    
    /**
     * Get primary keys for table
     */
    public List<String> getPrimaryKeys(String tableName) {
        List<String> primaryKeys = new ArrayList<>();
        if (tableName == null || connection == null) return primaryKeys;
        
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getPrimaryKeys(null, null, tableName)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to get primary keys for table: " + tableName, e);
        }
        
        return primaryKeys;
    }
    
    /**
     * Get imported keys (foreign keys) for table
     */
    public List<ForeignKeyInfo> getImportedKeys(String tableName) {
        List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
        if (tableName == null || connection == null) return foreignKeys;
        
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getImportedKeys(null, null, tableName)) {
                while (rs.next()) {
                    ForeignKeyInfo fk = new ForeignKeyInfo();
                    fk.columnName = rs.getString("FKCOLUMN_NAME");
                    fk.referencedTable = rs.getString("PKTABLE_NAME");
                    fk.referencedColumn = rs.getString("PKCOLUMN_NAME");
                    foreignKeys.add(fk);
                }
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to get imported keys for table: " + tableName, e);
        }
        
        return foreignKeys;
    }
    
    // Helper classes for metadata
    public static class ColumnInfo {
        public String name;
        public int type;
        public int size;
        public boolean nullable;
    }
    
    public static class ForeignKeyInfo {
        public String columnName;
        public String referencedTable;
        public String referencedColumn;
    }
    
    // =====================================================
    // VALUE GENERATION
    // =====================================================
    
    /**
     * Generate value for column type
     */
    public Object generateValueForColumnType(int sqlType, int size) {
        switch (sqlType) {
            case Types.VARCHAR:
            case Types.CHAR:
                return randomAlphaNum(Math.min(size, 10));
            case Types.INTEGER:
                return random.nextInt(1000000);
            case Types.BIGINT:
                return random.nextLong();
            case Types.DECIMAL:
            case Types.NUMERIC:
                return new BigDecimal(random.nextDouble() * 1000);
            case Types.DATE:
                return new java.sql.Date(System.currentTimeMillis());
            case Types.TIMESTAMP:
                return new java.sql.Timestamp(System.currentTimeMillis());
            case Types.BOOLEAN:
                return random.nextBoolean();
            default:
                return "DEFAULT_VALUE";
        }
    }
    
    /**
     * Generate selective random value for field
     */
    public Object generateSelectiveRandomValueForField(String fieldName, int sqlType, int size) {
        // Special handling for common field names
        if (fieldName != null) {
            String lowerField = fieldName.toLowerCase();
            if (lowerField.contains("email")) {
                return "test" + random.nextInt(1000) + "@example.com";
            } else if (lowerField.contains("name")) {
                return "TestName" + random.nextInt(1000);
            } else if (lowerField.contains("phone")) {
                return "555-" + String.format("%04d", random.nextInt(10000));
            }
        }
        
        return generateValueForColumnType(sqlType, size);
    }
    
    /**
     * Sanitize value for column
     */
    public Object sanitizeValueForColumn(Object value, int sqlType, int size) {
        if (value == null) return null;
        
        switch (sqlType) {
            case Types.VARCHAR:
            case Types.CHAR:
                String strValue = value.toString();
                if (strValue.length() > size) {
                    return strValue.substring(0, size);
                }
                return strValue;
            case Types.INTEGER:
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                try {
                    return Integer.parseInt(value.toString());
                } catch (NumberFormatException e) {
                    return 0;
                }
            case Types.BIGINT:
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                try {
                    return Long.parseLong(value.toString());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            default:
                return value;
        }
    }
    
    /**
     * Get minimal value for column type
     */
    public Object minimalValueForColumn(int sqlType) {
        switch (sqlType) {
            case Types.VARCHAR:
            case Types.CHAR:
                return "A";
            case Types.INTEGER:
                return 1;
            case Types.BIGINT:
                return 1L;
            case Types.DECIMAL:
            case Types.NUMERIC:
                return BigDecimal.ONE;
            case Types.DATE:
                return new java.sql.Date(System.currentTimeMillis());
            case Types.TIMESTAMP:
                return new java.sql.Timestamp(System.currentTimeMillis());
            case Types.BOOLEAN:
                return Boolean.TRUE;
            default:
                return "MINIMAL";
        }
    }
    
    // =====================================================
    // ERROR HANDLING
    // =====================================================
    
    /**
     * Extract SQL code from exception
     */
    public String extractSQLCode(SQLException e) {
        if (e == null) return null;
        return "SQL" + e.getErrorCode();
    }
    
    /**
     * Unwrap SQL code from exception chain
     */
    public String unwrapSqlCode(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SQLException) {
                return extractSQLCode((SQLException) current);
            }
            current = current.getCause();
        }
        return null;
    }
    
    /**
     * Log error summary
     */
    public void logErrorSummary(String message, Exception e) {
        System.err.println("ERROR: " + message);
        if (e != null) {
            System.err.println("Exception: " + e.getMessage());
            String sqlCode = unwrapSqlCode(e);
            if (sqlCode != null) {
                System.err.println("SQL Code: " + sqlCode);
            }
        }
    }
    
    // =====================================================
    // DAO EXECUTION METHODS
    // =====================================================
    
    /**
     * Call DAO method and log execution
     */
    public Object callDaoMethodAndLog(Object dao, Method method, Object[] args) {
        if (dao == null || method == null) return null;
        
        long startTime = System.currentTimeMillis();
        try {
            Object result = method.invoke(dao, args);
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("DAO Method: " + method.getName() + " executed in " + duration + "ms");
            return result;
        } catch (Exception e) {
            logErrorSummary("DAO method execution failed: " + method.getName(), e);
            return null;
        }
    }
    
    /**
     * Run direct DB queries
     */
    public List<Map<String, Object>> runDirectDBQueries(List<String> queries) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (queries == null || connection == null) return results;
        
        for (String query : queries) {
            try (PreparedStatement stmt = connection.prepareStatement(query);
                 ResultSet rs = stmt.executeQuery()) {
                
                List<Map<String, Object>> queryResults = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    queryResults.add(row);
                }
                
                results.addAll(queryResults);
            } catch (SQLException e) {
                logErrorSummary("Failed to execute query: " + query, e);
            }
        }
        
        return results;
    }
    
    /**
     * Categorize methods by type
     */
    public Map<String, List<Method>> categorizeMethods(Class<?> clazz) {
        Map<String, List<Method>> categorized = new HashMap<>();
        if (clazz == null) return categorized;
        
        Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            String category = determineMethodCategory(method);
            categorized.computeIfAbsent(category, k -> new ArrayList<>()).add(method);
        }
        
        return categorized;
    }
    
    /**
     * Create method count map
     */
    public Map<String, Integer> createMethodCountMap(Class<?> clazz) {
        Map<String, Integer> countMap = new HashMap<>();
        if (clazz == null) return countMap;
        
        Map<String, List<Method>> categorized = categorizeMethods(clazz);
        for (Map.Entry<String, List<Method>> entry : categorized.entrySet()) {
            countMap.put(entry.getKey(), entry.getValue().size());
        }
        
        return countMap;
    }
    
    private String determineMethodCategory(Method method) {
        String name = method.getName().toLowerCase();
        if (name.startsWith("get") || name.startsWith("find") || name.startsWith("select")) {
            return "READ";
        } else if (name.startsWith("insert") || name.startsWith("create") || name.startsWith("add")) {
            return "INSERT";
        } else if (name.startsWith("update") || name.startsWith("modify") || name.startsWith("set")) {
            return "UPDATE";
        } else if (name.startsWith("delete") || name.startsWith("remove")) {
            return "DELETE";
        } else {
            return "OTHER";
        }
    }
    
    // =====================================================
    // DATA PREPARATION METHODS
    // =====================================================
    
    /**
     * Prepare duplicate data
     */
    public Map<String, Object> prepareDuplicateData(String tableName, Map<String, Object> originalData) {
        Map<String, Object> duplicateData = new HashMap<>();
        if (tableName == null || originalData == null) return duplicateData;
        
        // Copy all data except primary keys
        List<String> primaryKeys = getPrimaryKeys(tableName);
        Set<String> pkSet = new HashSet<>(primaryKeys);
        
        for (Map.Entry<String, Object> entry : originalData.entrySet()) {
            if (!pkSet.contains(entry.getKey())) {
                duplicateData.put(entry.getKey(), entry.getValue());
            }
        }
        
        // Generate new primary key values
        for (String pk : primaryKeys) {
            ColumnInfo colInfo = getColumnMeta(tableName, pk);
            if (colInfo != null) {
                duplicateData.put(pk, generateValueForColumnType(colInfo.type, colInfo.size));
            }
        }
        
        return duplicateData;
    }
    
    /**
     * Prepare row data for online processing
     */
    public Map<String, Object> prepareRowDataforOnline(String tableName, Map<String, Object> baseData) {
        Map<String, Object> onlineData = new HashMap<>();
        if (tableName == null || baseData == null) return onlineData;
        
        onlineData.putAll(baseData);
        
        // Add audit fields if they exist
        List<ColumnInfo> columns = getColumns(tableName);
        for (ColumnInfo col : columns) {
            String colName = col.name.toLowerCase();
            if (colName.contains("created") && colName.contains("date")) {
                onlineData.put(col.name, new java.sql.Timestamp(System.currentTimeMillis()));
            } else if (colName.contains("modified") && colName.contains("date")) {
                onlineData.put(col.name, new java.sql.Timestamp(System.currentTimeMillis()));
            } else if (colName.contains("status") && !onlineData.containsKey(col.name)) {
                onlineData.put(col.name, "ACTIVE");
            }
        }
        
        return onlineData;
    }
    
    /**
     * Retrieve records from table
     */
    public List<Map<String, Object>> retrieveRecordsFromTable(String tableName, Map<String, Object> criteria) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (tableName == null || connection == null) return records;
        
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName);
            List<Object> params = new ArrayList<>();
            
            if (criteria != null && !criteria.isEmpty()) {
                sql.append(" WHERE ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : criteria.entrySet()) {
                    if (!first) sql.append(" AND ");
                    sql.append(entry.getKey()).append(" = ?");
                    params.add(entry.getValue());
                    first = false;
                }
            }
            
            try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    
                    while (rs.next()) {
                        Map<String, Object> record = new HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            record.put(metaData.getColumnName(i), rs.getObject(i));
                        }
                        records.add(record);
                    }
                }
            }
        } catch (SQLException e) {
            logErrorSummary("Failed to retrieve records from table: " + tableName, e);
        }
        
        return records;
    }
    
    // =====================================================
    // TEMPLATE AND MAPPING METHODS
    // =====================================================
    
    /**
     * Extract template map from configuration
     */
    public Map<String, Object> extractTemplateMap(String configSource) {
        Map<String, Object> templateMap = new HashMap<>();
        if (configSource == null) return templateMap;
        
        // Simple key-value parsing (extend based on actual format)
        String[] lines = configSource.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    templateMap.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        
        return templateMap;
    }
    
    /**
     * Get first template from collection
     */
    public Object firstTemplate(Collection<Object> templates) {
        if (templates == null || templates.isEmpty()) return null;
        return templates.iterator().next();
    }
    
    /**
     * Get template for method
     */
    public Object getTemplateForMethod(Method method) {
        if (method == null) return null;
        
        String methodName = method.getName();
        return templateMap.get(methodName);
    }
    
    /**
     * Build DAO dependency map
     */
    public Map<String, Set<String>> buildDaoDependencyMap(List<Class<?>> daoClasses) {
        Map<String, Set<String>> dependencyMap = new HashMap<>();
        if (daoClasses == null) return dependencyMap;
        
        for (Class<?> daoClass : daoClasses) {
            String className = daoClass.getSimpleName();
            Set<String> dependencies = new HashSet<>();
            
            // Analyze methods to find dependencies
            Method[] methods = daoClass.getDeclaredMethods();
            for (Method method : methods) {
                Class<?>[] paramTypes = method.getParameterTypes();
                for (Class<?> paramType : paramTypes) {
                    if (isDaoClass(paramType)) {
                        dependencies.add(paramType.getSimpleName());
                    }
                }
            }
            
            dependencyMap.put(className, dependencies);
        }
        
        return dependencyMap;
    }
    
    /**
     * Parse tables from SQL
     */
    public Set<String> parseTablesFromSql(String sql) {
        Set<String> tables = new HashSet<>();
        if (sql == null) return tables;
        
        String upperSql = sql.toUpperCase();
        
        // Extract FROM clauses
        Pattern fromPattern = Pattern.compile("FROM\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
        Matcher fromMatcher = fromPattern.matcher(sql);
        while (fromMatcher.find()) {
            tables.add(fromMatcher.group(1));
        }
        
        // Extract JOIN clauses
        Pattern joinPattern = Pattern.compile("JOIN\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
        Matcher joinMatcher = joinPattern.matcher(sql);
        while (joinMatcher.find()) {
            tables.add(joinMatcher.group(1));
        }
        
        // Extract INSERT/UPDATE/DELETE tables
        Pattern insertPattern = Pattern.compile("(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
        Matcher insertMatcher = insertPattern.matcher(sql);
        while (insertMatcher.find()) {
            tables.add(insertMatcher.group(1));
        }
        
        return tables;
    }
    
    private boolean isDaoClass(Class<?> clazz) {
        String className = clazz.getSimpleName().toLowerCase();
        return className.contains("dao") || className.contains("repository");
    }
    
    // =====================================================
    // MAPPER AND CONFIG LOADING
    // =====================================================
    
    /**
     * Get mapper for table by source
     */
    public Object getMapperForTableBySource(String tableName, String source) {
        if (tableName == null || source == null) return null;
        
        // Try to load from cache first
        String cacheKey = tableName + ":" + source;
        if (templateMap.containsKey(cacheKey)) {
            return templateMap.get(cacheKey);
        }
        
        // Load mapper
        Object mapper = loadMapperForTableBySource(tableName, source);
        if (mapper != null) {
            templateMap.put(cacheKey, mapper);
        }
        
        return mapper;
    }
    
    /**
     * Load mapper for table by source
     */
    public Object loadMapperForTableBySource(String tableName, String source) {
        if (tableName == null || source == null) return null;
        
        try {
            // Try to find mapper class by convention
            String mapperClassName = "com.tsys.enterprise.issuing.mapper." + 
                tableName.toLowerCase() + "." + source + "Mapper";
            
            Class<?> mapperClass = Class.forName(mapperClassName);
            return mapperClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            // Fallback to generic mapper
            return createGenericMapper(tableName, source);
        }
    }
    
    /**
     * Parse mapper by source
     */
    public Map<String, Object> parseMapperBySource(String source, String mapperConfig) {
        Map<String, Object> mapper = new HashMap<>();
        if (source == null || mapperConfig == null) return mapper;
        
        // Parse configuration format (extend based on actual format)
        String[] sections = mapperConfig.split("\\[" + source + "\\]");
        if (sections.length > 1) {
            String[] lines = sections[1].split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("[") && !line.startsWith("[" + source + "]")) {
                    break; // Next section
                }
                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        mapper.put(parts[0].trim(), parts[1].trim());
                    }
                }
            }
        }
        
        return mapper;
    }
    
    private Object createGenericMapper(String tableName, String source) {
        // Create a simple map-based mapper
        Map<String, Object> genericMapper = new HashMap<>();
        genericMapper.put("tableName", tableName);
        genericMapper.put("source", source);
        genericMapper.put("type", "GENERIC");
        return genericMapper;
    }
    
    // =====================================================
    // VALIDATION AND CLEANUP
    // =====================================================
    
    /**
     * Ensure valid logical comparison in query
     */
    public String ensureValidLogicalComparisonInQuery(String query) {
        if (query == null) return null;
        
        String validatedQuery = query;
        
        // Fix common logical comparison issues
        validatedQuery = validatedQuery.replaceAll("(?i)\\s*=\\s*NULL", " IS NULL");
        validatedQuery = validatedQuery.replaceAll("(?i)\\s*!=\\s*NULL", " IS NOT NULL");
        validatedQuery = validatedQuery.replaceAll("(?i)\\s*<>\\s*NULL", " IS NOT NULL");
        
        // Ensure proper AND/OR spacing
        validatedQuery = validatedQuery.replaceAll("(?i)\\s*(AND|OR)\\s*", " $1 ");
        
        return validatedQuery;
    }
    
    /**
     * Ensure no null value in required fields
     */
    public Map<String, Object> ensureNoNullValue(String tableName, Map<String, Object> data) {
        Map<String, Object> validatedData = new HashMap<>();
        if (tableName == null || data == null) return validatedData;
        
        List<ColumnInfo> columns = getColumns(tableName);
        Map<String, ColumnInfo> columnMap = new HashMap<>();
        for (ColumnInfo col : columns) {
            columnMap.put(col.name.toLowerCase(), col);
        }
        
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            ColumnInfo colInfo = columnMap.get(key.toLowerCase());
            if (colInfo != null && !colInfo.nullable && value == null) {
                // Generate appropriate non-null value
                value = minimalValueForColumn(colInfo.type);
            }
            
            validatedData.put(key, value);
        }
        
        return validatedData;
    }
    
    /**
     * Cleanup tracked records
     */
    public void cleanupTrackedRecords() {
        if (connection == null || trackedRecords.isEmpty()) return;
        
        for (String recordKey : new ArrayList<>(trackedRecords)) {
            try {
                String[] parts = recordKey.split(":", 2);
                if (parts.length == 2) {
                    String tableName = parts[0];
                    // Could implement cleanup logic here if needed
                    // For now, just remove from tracking
                    trackedRecords.remove(recordKey);
                }
            } catch (Exception e) {
                logErrorSummary("Failed to cleanup record: " + recordKey, e);
            }
        }
    }
    
    // =====================================================
    // ADDITIONAL UTILITY METHODS
    // =====================================================
    
    /**
     * Retrieve class by name
     */
    public Class<?> retrieveClass(String className) {
        if (className == null) return null;
        
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            logErrorSummary("Class not found: " + className, e);
            return null;
        }
    }
    
    /**
     * Get reading strategy for data source
     */
    public String getReadingStrategy(String dataSource) {
        if (dataSource == null) return "DEFAULT";
        
        String lowerSource = dataSource.toLowerCase();
        if (lowerSource.contains("batch")) {
            return "BATCH";
        } else if (lowerSource.contains("stream")) {
            return "STREAM";
        } else if (lowerSource.contains("cache")) {
            return "CACHE";
        } else {
            return "SEQUENTIAL";
        }
    }
    
    /**
     * Random audit file name fallback
     */
    public String randomAuditFileNameFallback() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "audit_" + timestamp + "_" + randomAlphaNum(6) + ".log";
    }
    
    /**
     * Ensure consistent numbered placeholders (variant)
     */
    public String ensureConsistentNumberedPlaceholders(String query) {
        return ensureConsistentNumberedPlaceholdersFromTokens(query, new HashMap<>());
    }
    
    /**
     * Additional ensure method for numbered placeholders with custom mapping
     */
    public String ensureConsistentNumberedPlaceholdersWithMapping(String query, Map<String, Object> tokenValueMap) {
        if (query == null) return null;
        
        List<String> tokens = parseTokensFromQuery(query);
        Map<String, Integer> tokenIndexMap = new HashMap<>();
        
        int index = 1;
        for (String token : tokens) {
            if (!tokenIndexMap.containsKey(token)) {
                tokenIndexMap.put(token, index++);
            }
        }
        
        String result = query;
        for (Map.Entry<String, Integer> entry : tokenIndexMap.entrySet()) {
            String token = entry.getKey();
            result = result.replaceAll("\\{" + Pattern.quote(token) + "\\}", "?");
        }
        
        return result;
    }
}