///usr/bin/env jbang "$0" "$@" & exit /b %ERRORLEVEL%
//DEPS org.slf4j:slf4j-api:2.0.13
//DEPS ch.qos.logback:logback-classic:1.5.6
//DEPS org.postgresql:postgresql:42.7.3
//DEPS com.oracle.database.jdbc:ojdbc11:23.4.0.24.05
package org.raisercostin.mjdbc;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/*
JDBC URL format:
  jdbc:mjdbc:default=ds1;ds1=<JDBC-URL-1>;ds2=<JDBC-URL-2>[;prop.key=val...]

Examples:
  jdbc:mjdbc:default=ora;ora=jdbc:oracle:thin:@//host:1521/xepdb1?user=u&password=p;pg=jdbc:postgresql://host:5432/app?user=u&password=p
  jdbc:mjdbc:default=pg;pg=jdbc:postgresql://...;ora=jdbc:oracle:thin:@...

Routing hint per statement:
  \/*ds:pg*\/ SELECT ...        -- routes this statement to the backend labeled pg
No hint uses the default backend.

Notes:
- Passthrough: SQL is forwarded as-is after stripping only the leading comment hint.
- Transactions: connection locks onto the first-used backend until commit/rollback. Mixing backends in one tx is rejected.
*/

public final class MjdbcDriver implements java.sql.Driver {
  static {
    try {
      DriverManager.registerDriver(new MjdbcDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }

    // best-effort auto-registration of common backends present on classpath
    try {
      Class.forName("org.postgresql.Driver");
    } catch (Throwable ignore) {
    }
    try {
      Class.forName("oracle.jdbc.OracleDriver");
    } catch (Throwable ignore) {
    }
  }

  // Allow running as a script to show help
  public static void main(String[] args) {
    System.out.println("mjdb c JDBC driver loaded. Use URL: jdbc:mjdbc:default=ds1;ds1=<JDBC-URL-1>;ds2=<JDBC-URL-2>");
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url))
      return null;
    Parsed p = Parsed.parse(url);
    return new RoutedConnection(p);
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith("jdbc:mjdbc:");
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 1;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("mjdb c");
  }

  // Parse jdbc:mjdbc:default=ds1;ds1=jdbc:...;ds2=jdbc:...
  static final class Parsed {
    final String defaultLabel;
    final Map<String, String> labelToUrl;
    final Map<String, String> extraProps;

    private Parsed(String def, Map<String, String> m, Map<String, String> props) {
      this.defaultLabel = def;
      this.labelToUrl = m;
      this.extraProps = props;
    }

    static Parsed parse(String url) throws SQLException {
      String spec = url.substring("jdbc:mjdbc:".length());
      Map<String, String> map = new LinkedHashMap<>();
      Map<String, String> props = new LinkedHashMap<>();
      String def = null;
      for (String part : spec.split(";", -1)) {
        if (part.isEmpty())
          continue;
        int eq = part.indexOf("=");
        if (eq < 0)
          throw new SQLException("Malformed segment: " + part);
        String k = part.substring(0, eq).trim();
        String v = part.substring(eq + 1).trim();
        if (k.equals("default")) {
          def = v;
        } else if (v.startsWith("jdbc:")) {
          map.put(k, v);
        } else {
          props.put(k, v);
        }
      }
      if (def == null || !map.containsKey(def))
        throw new SQLException("default label missing or unknown.");
      // store sticky in extra props; default tx
      props.putIfAbsent("sticky", "tx"); // values: tx | session | statement
      return new Parsed(def, map, props);
    }
  }

  static final class RoutedConnection implements Connection {
    private final Parsed cfg;
    private final Map<String, Driver> loadedDrivers = new HashMap<>();
    private Connection active; // current backend connection for this logical connection
    private String activeLabel; // label of active backend
    private boolean autoCommit = true;
    private boolean closed;

    RoutedConnection(Parsed p) {
      this.cfg = p;
    }

    private String sticky() {
      String v = cfg.extraProps.get("sticky");
      return v == null ? "tx" : v;
    }

    // Acquire a backend connection by label lazily
    private Connection backend(String label) throws SQLException {
      String jdbcUrl = cfg.labelToUrl.get(label);
      if (jdbcUrl == null)
        throw new SQLException("Unknown backend label: " + label);
      // Use DriverManager to open; credentials can be in URL
      // ensure vendor driver is loaded when called by URL
      if (jdbcUrl.startsWith("jdbc:postgresql:")) {
        try {
          Class.forName("org.postgresql.Driver");
        } catch (Throwable ignore) {
        }
      } else if (jdbcUrl.startsWith("jdbc:oracle:")) {
        try {
          Class.forName("oracle.jdbc.OracleDriver");
        } catch (Throwable ignore) {
        }
      }
      return DriverManager.getConnection(jdbcUrl);
    }

    private void ensureBackendForTx(String label) throws SQLException {
      String mode = sticky();
      if ("statement".equalsIgnoreCase(mode)) {
        // handled per statement; nothing to pin here
        return;
      }
      if (active == null) {
        active = backend(label);
        activeLabel = label;
        return;
      }
      if (Objects.equals(activeLabel, label))
        return;

      if ("session".equalsIgnoreCase(mode)) {
        try {
          active.close();
        } catch (SQLException ignore) {
        }
        active = backend(label);
        activeLabel = label;
        return;
      }

      // tx mode
      if (autoCommit) {
        try {
          active.close();
        } catch (SQLException ignore) {
        }
        active = backend(label);
        activeLabel = label;
      } else {
        throw new SQLException(
            "Cannot switch backend within an open transaction. sticky=tx. Use COMMIT or set sticky=session or sticky=statement.");
      }
    }

    static String parseHintLabel(String sql, String def) throws SQLException {
      String s = sql.trim();
      if (s.startsWith("/*ds:")) {
        int e = s.indexOf("*/");
        if (e < 0)
          throw new SQLException("Malformed routing hint. Expected closing */");
        String inside = s.substring(5, e).trim();
        if (inside.isEmpty())
          throw new SQLException("Empty ds label in hint.");
        return inside;
      }
      return def;
    }

    static String stripHint(String sql) {
      String s = sql.trim();
      if (s.startsWith("/*ds:")) {
        int e = s.indexOf("*/");
        if (e > 0)
          return s.substring(e + 2).trim();
      }
      return sql;
    }

    // Statement factories
    @Override
    public Statement createStatement() throws SQLException {
      return new RoutedStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareStatement(body);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareCall(body);
    }

    // Transaction control
    @Override
    public void setAutoCommit(boolean ac) throws SQLException {
      this.autoCommit = ac;
      if (active != null)
        active.setAutoCommit(ac);
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
      return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
      if (active != null)
        active.commit();
    }

    @Override
    public void rollback() throws SQLException {
      if (active != null)
        active.rollback();
    }

    // Close
    @Override
    public void close() throws SQLException {
      if (active != null)
        active.close();
      closed = true;
    }

    @Override
    public boolean isClosed() throws SQLException {
      return closed;
    }

    // Delegate common metadata ops to active or default backend if not yet chosen
    private Connection metaConn() throws SQLException {
      if (active != null)
        return active;
      // open default only for metadata
      ensureBackendForTx(cfg.defaultLabel);
      return active;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
      return metaConn().getMetaData();
    }

    // Boilerplate minimal impl
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
      metaConn().setReadOnly(readOnly);
    }

    @Override
    public boolean isReadOnly() throws SQLException {
      return metaConn().isReadOnly();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
      metaConn().setCatalog(catalog);
    }

    @Override
    public String getCatalog() throws SQLException {
      return metaConn().getCatalog();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
      metaConn().setTransactionIsolation(level);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
      return metaConn().getTransactionIsolation();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
      return metaConn().getWarnings();
    }

    @Override
    public void clearWarnings() throws SQLException {
      metaConn().clearWarnings();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      if (iface.isInstance(this))
        return iface.cast(this);
      if (active != null && iface.isInstance(active))
        return iface.cast(active);
      throw new SQLException("unwrap not supported for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return iface.isInstance(this);
    }

    // not implemented methods throw for brevity
    @Override
    public String nativeSQL(String sql) {
      return sql;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
      return metaConn().setSavepoint();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
      metaConn().rollback(savepoint);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
      metaConn().releaseSavepoint(savepoint);
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
      metaConn().setHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
      return metaConn().getHoldability();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
      return metaConn().getTypeMap();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
      metaConn().setTypeMap(map);
    }

    @Override
    public Clob createClob() throws SQLException {
      return metaConn().createClob();
    }

    @Override
    public Blob createBlob() throws SQLException {
      return metaConn().createBlob();
    }

    @Override
    public NClob createNClob() throws SQLException {
      return metaConn().createNClob();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
      return metaConn().createSQLXML();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
      return metaConn().isValid(timeout);
    }

    @Override
    public void setClientInfo(String name, String value) {
      /* ignore */ }

    @Override
    public void setClientInfo(Properties properties) {
      /* ignore */ }

    @Override
    public String getSchema() throws SQLException {
      return metaConn().getSchema();
    }

    @Override
    public void setSchema(String schema) throws SQLException {
      metaConn().setSchema(schema);
    }

    @Override
    public void abort(java.util.concurrent.Executor executor) throws SQLException {
      if (active != null)
        active.abort(executor);
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException {
      metaConn().setNetworkTimeout(executor, milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
      return metaConn().getNetworkTimeout();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
      return metaConn().createArrayOf(typeName, elements);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
      return metaConn().createStruct(typeName, attributes);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
      return metaConn().getClientInfo();
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
      return metaConn().getClientInfo(name);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareStatement(body, autoGeneratedKeys);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareStatement(body, columnIndexes);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareStatement(body, columnNames);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareCall(body, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareCall(body, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
      ensureBackendForTx(cfg.defaultLabel);
      return active.createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
      ensureBackendForTx(cfg.defaultLabel);
      return active.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareStatement(body, resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      ensureBackendForTx(label);
      String body = stripHint(sql);
      return active.prepareStatement(body, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
      return metaConn().setSavepoint(name);
    }

    Connection openBackendForStatement(String label) throws SQLException {
      // used when sticky=statement to get a fresh backend per statement
      return backend(label);
    }
  }

  static final class RoutedStatement implements Statement {
    private final RoutedConnection parent;

    RoutedStatement(RoutedConnection p) {
      this.parent = p;
    }

    private Connection route(String sql) throws SQLException {
      String label = RoutedConnection.parseHintLabel(sql, parent.cfg.defaultLabel);
      parent.ensureBackendForTx(label);
      return parent.active;
    }

    private String body(String sql) {
      return RoutedConnection.stripHint(sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
      try (Statement s = route(sql).createStatement()) {
        return s.execute(body(sql));
      }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
      Statement s = route(sql).createStatement();
      return s.executeQuery(body(sql));
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
      try (Statement s = route(sql).createStatement()) {
        return s.executeUpdate(body(sql));
      }
    }

    // Minimal passthroughs
    @Override
    public void close() {
    }

    @Override
    public int getMaxFieldSize() {
      return 0;
    }

    @Override
    public void setMaxFieldSize(int max) {
    }

    @Override
    public int getMaxRows() {
      return 0;
    }

    @Override
    public void setMaxRows(int max) {
    }

    @Override
    public void setEscapeProcessing(boolean enable) {
    }

    @Override
    public int getQueryTimeout() {
      return 0;
    }

    @Override
    public void setQueryTimeout(int seconds) {
    }

    @Override
    public void cancel() {
    }

    @Override
    public SQLWarning getWarnings() {
      return null;
    }

    @Override
    public void clearWarnings() {
    }

    @Override
    public void setCursorName(String name) {
    }

    @Override
    public ResultSet getResultSet() {
      return null;
    }

    @Override
    public int getUpdateCount() {
      return -1;
    }

    @Override
    public boolean getMoreResults() {
      return false;
    }

    @Override
    public void setFetchDirection(int direction) {
    }

    @Override
    public int getFetchDirection() {
      return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) {
    }

    @Override
    public int getFetchSize() {
      return 0;
    }

    @Override
    public int getResultSetConcurrency() {
      return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() {
      return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public void addBatch(String sql) {
      throw new UnsupportedOperationException("Use PreparedStatement for batch");
    }

    @Override
    public void clearBatch() {
    }

    @Override
    public int[] executeBatch() {
      return new int[0];
    }

    @Override
    public Connection getConnection() {
      return parent;
    }

    @Override
    public boolean getMoreResults(int current) {
      return false;
    }

    @Override
    public ResultSet getGeneratedKeys() {
      return null;
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
      return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
      return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
      return executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
      return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
      return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
      return execute(sql);
    }

    @Override
    public int getResultSetHoldability() {
      return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public void setPoolable(boolean poolable) {
    }

    @Override
    public boolean isPoolable() {
      return false;
    }

    @Override
    public void closeOnCompletion() {
    }

    @Override
    public boolean isCloseOnCompletion() {
      return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("unwrap not supported");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

}
