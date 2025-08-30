///usr/bin/env jbang "$0" "$@" & exit /b %ERRORLEVEL%
//DEPS org.slf4j:slf4j-api:2.0.13
//DEPS ch.qos.logback:logback-classic:1.5.6
//DEPS org.postgresql:postgresql:42.7.3
//DEPS com.oracle.database.jdbc:ojdbc11:23.4.0.24.05
package org.raisercostin.mjdbc;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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
- Transactions sticky modes via URL prop 'sticky':
    sticky=tx        (default)  pin backend during a transaction, switch only when autocommit=true
    sticky=session              pin backend for the session; switch closes previous backend
    sticky=statement            open a fresh backend per statement (good for DBeaver reuse)
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

  public static void main(String[] args) {
    System.out.println("mjdbc JDBC driver loaded. Example URL:");
    System.out.println(
        "jdbc:mjdbc:default=pg;sticky=statement;pg=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=secret;ora=jdbc:oracle:thin:@//host:1521/XEPDB1?user=u&password=p");
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
    return 2;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("mjdbc");
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
      // default sticky
      props.putIfAbsent("sticky", "tx"); // tx | session | statement
      return new Parsed(def, map, props);
    }
  }

  static final class RoutedConnection implements Connection {
    private final Parsed cfg;
    @SuppressWarnings("unused")
    private final Map<String, Driver> loadedDrivers = new HashMap<>();
    Connection active; // current backend connection for this logical connection
    String activeLabel; // label of active backend
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

    Connection openBackendForStatement(String label) throws SQLException {
      return backend(label);
    }

    private void ensureBackendForTx(String label) throws SQLException {
      String mode = sticky();
      if ("statement".equalsIgnoreCase(mode)) {
        // handled per-statement by callers
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

    // ---------- Statement factories ----------
    @Override
    public Statement createStatement() throws SQLException {
      return new RoutedStatement(this);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
      return new RoutedStatement(this, resultSetType, resultSetConcurrency, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
      return new RoutedStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    // ---------- PreparedStatement factories ----------
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareStatement(body), c);
      }
      ensureBackendForTx(label);
      return active.prepareStatement(body);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareStatement(body, autoGeneratedKeys), c);
      }
      ensureBackendForTx(label);
      return active.prepareStatement(body, autoGeneratedKeys);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareStatement(body, columnIndexes), c);
      }
      ensureBackendForTx(label);
      return active.prepareStatement(body, columnIndexes);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareStatement(body, columnNames), c);
      }
      ensureBackendForTx(label);
      return active.prepareStatement(body, columnNames);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareStatement(body, resultSetType, resultSetConcurrency), c);
      }
      ensureBackendForTx(label);
      return active.prepareStatement(body, resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareStatement(body, resultSetType, resultSetConcurrency, resultSetHoldability), c);
      }
      ensureBackendForTx(label);
      return active.prepareStatement(body, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    // ---------- CallableStatement factories ----------
    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareCall(body), c);
      }
      ensureBackendForTx(label);
      return active.prepareCall(body);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareCall(body, resultSetType, resultSetConcurrency), c);
      }
      ensureBackendForTx(label);
      return active.prepareCall(body, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        return autoClose(c.prepareCall(body, resultSetType, resultSetConcurrency, resultSetHoldability), c);
      }
      ensureBackendForTx(label);
      return active.prepareCall(body, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    // ---------- Transaction control ----------
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

    // ---------- Close ----------
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

    // ---------- Metadata delegation ----------
    private Connection metaConn() throws SQLException {
      if (active != null)
        return active;

      // Always open a real backend for metadata, even in sticky=statement.
      // Use the default label so DBeaver can introspect schemas/tables.
      String def = cfg.defaultLabel;
      active = backend(def);
      activeLabel = def;
      if (!autoCommit)
        active.setAutoCommit(false);

      // optional debug
      logRoute("open-backend(meta)", def, "/*meta*/");

      return active;
    }

    private void logRoute(String stage, String label, String sql) {
      if (!debug())
        return;
      String msg = "[mjdbc] " + stage + " sticky=" + sticky() + " active=" + (activeLabel == null ? "-" : activeLabel)
          + " -> ds=" + label + " sql=\"" + truncate(stripHint(sql), 160).replace("\n", " ") + "\"";
      if ("jul".equalsIgnoreCase(cfg.extraProps.get("log"))) {
        Logger.getLogger("mjdbc").info(msg);
      } else {
        System.err.println(msg);
      }
    }

    private static String truncate(String s, int n) {
      return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private boolean debug() {
      String d = cfg.extraProps.get("debug");
      return d != null && (d.equalsIgnoreCase("true") || d.equalsIgnoreCase("1") || d.equalsIgnoreCase("yes"));
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
      return metaConn().getMetaData();
    }

    // ---------- Boilerplate delegation ----------
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
    public Savepoint setSavepoint(String name) throws SQLException {
      return metaConn().setSavepoint(name);
    }

    // ---------- small helpers to autoclose backend on statement close in
    // sticky=statement ----------
    private PreparedStatement autoClose(PreparedStatement d, Connection c) {
      return (PreparedStatement) Proxy.newProxyInstance(d.getClass().getClassLoader(),
          new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
              try {
                return method.invoke(d, args);
              } finally {
                try {
                  c.close();
                } catch (Throwable ignore) {
                }
              }
            }
            return method.invoke(d, args);
          });
    }

    private CallableStatement autoClose(CallableStatement d, Connection c) {
      return (CallableStatement) Proxy.newProxyInstance(d.getClass().getClassLoader(),
          new Class<?>[] { CallableStatement.class }, (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
              try {
                return method.invoke(d, args);
              } finally {
                try {
                  c.close();
                } catch (Throwable ignore) {
                }
              }
            }
            return method.invoke(d, args);
          });
    }
  }

  static final class RoutedStatement implements Statement {
    private final RoutedConnection parent;
    private final int rsType;
    private final int rsConcurrency;
    private final int rsHoldability;

    RoutedStatement(RoutedConnection p) {
      this(p, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    RoutedStatement(RoutedConnection p, int type, int concurrency, int holdability) {
      this.parent = p;
      this.rsType = type;
      this.rsConcurrency = concurrency;
      this.rsHoldability = holdability;
    }

    private Connection routeOpen(String sql) throws SQLException {
      String label = RoutedConnection.parseHintLabel(sql, parent.cfg.defaultLabel);
      if ("statement".equalsIgnoreCase(parent.sticky())) {
        return parent.openBackendForStatement(label);
      }
      parent.ensureBackendForTx(label);
      return parent.active;
    }

    private void routeClose(Connection c) {
      try {
        if (c != null && "statement".equalsIgnoreCase(parent.sticky()))
          c.close();
      } catch (SQLException ignore) {
      }
    }

    @Override
    public boolean execute(String sql) throws SQLException {
      Connection c = routeOpen(sql);
      try (Statement s = c.createStatement(rsType, rsConcurrency, rsHoldability)) {
        return s.execute(RoutedConnection.stripHint(sql));
      } finally {
        routeClose(c);
      }
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
      Connection c = routeOpen(sql);
      Statement s = c.createStatement(rsType, rsConcurrency, rsHoldability);
      try {
        return s.executeQuery(RoutedConnection.stripHint(sql));
      } catch (SQLException e) {
        try {
          s.close();
        } catch (SQLException ignore) {
        }
        routeClose(c);
        throw e;
      }
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
      Connection c = routeOpen(sql);
      try (Statement s = c.createStatement(rsType, rsConcurrency, rsHoldability)) {
        return s.executeUpdate(RoutedConnection.stripHint(sql));
      } finally {
        routeClose(c);
      }
    }

    // -------- Minimal passthroughs / stubs --------
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
      return rsConcurrency;
    }

    @Override
    public int getResultSetType() {
      return rsType;
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
      return rsHoldability;
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
