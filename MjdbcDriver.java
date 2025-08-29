///usr/bin/env jbang "$0" "$@" & exit /b %ERRORLEVEL%
//DEPS org.slf4j:slf4j-api:2.0.13
//DEPS ch.qos.logback:logback-classic:1.5.6
//DEPS org.postgresql:postgresql:42.7.3
//DEPS com.oracle.database.jdbc:ojdbc11:23.4.0.24.05
package org.raisercostin.mjdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

/*
JDBC URL format:
  jdbc:mjdbc:default=<label>;[sticky=tx|session|statement];[debug=true];<label>=<jdbc-url>;...

Examples:
  jdbc:mjdbc:default=ora;sticky=statement;debug=true;
    ora=jdbc:oracle:thin:@//host:1521/xepdb1?user=u&password=p;
    pg=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=secret

Routing hint per statement:
  /\*ds:pg*\/ SELECT ...
*/

public final class MjdbcDriver implements java.sql.Driver {
  private static final Logger log = LoggerFactory.getLogger("mjdbc");

  static {
    try {
      DriverManager.registerDriver(new MjdbcDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }

    try {
      Class.forName("org.postgresql.Driver");
    } catch (Throwable ignore) {
    }
    try {
      Class.forName("oracle.jdbc.OracleDriver");
    } catch (Throwable ignore) {
    }

    // Optional RichLogback autoconfig if present
    try {
      Class<?> cls = Class.forName("com.namekis.utils.RichLogback");
      cls.getMethod("configureLogbackByVerbosity", int.class, boolean.class, boolean.class).invoke(null, 3, false,
          true);
      log.info("RichLogback configured (verbosity=3, color=true)");
    } catch (Throwable ignore) {
    }
  }

  public static void main(String[] args) {
    log.info("mjdbc driver loaded.");
    log.info(
        "Example URL: jdbc:mjdbc:default=pg;sticky=statement;verbosity=3;pg=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=secret;ora=jdbc:oracle:thin:@//host:1521/XEPDB1?user=u&password=p");
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url))
      return null;
    Parsed p = Parsed.parse(url);
    try {
      String vv = p.props.get("verbosity");
      int verbosity = (vv == null) ? 4 : Integer.parseInt(vv);
      Class<?> cls = Class.forName("com.namekis.utils.RichLogback");
      cls.getMethod("configureLogbackByVerbosity", int.class, boolean.class, boolean.class).invoke(null, verbosity,
          false, true);
      log.info("mjdbc logging configured via RichLogback (verbosity={})", verbosity);
    } catch (Throwable e) {
      log.warn("Couldn't configure logging", e);
    }
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
    return 4;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public java.util.logging.Logger getParentLogger() {
    return java.util.logging.Logger.getLogger("mjdbc");
  }

  // ---------- URL parsing ----------
  static final class Parsed {
    final String defaultLabel;
    final Map<String, String> labelToUrl;
    final Map<String, String> props;

    private Parsed(String def, Map<String, String> map, Map<String, String> props) {
      this.defaultLabel = def;
      this.labelToUrl = map;
      this.props = props;
    }

    static Parsed parse(String url) throws SQLException {
      String spec = url.substring("jdbc:mjdbc:".length());
      Map<String, String> map = new LinkedHashMap<>();
      Map<String, String> props = new LinkedHashMap<>();
      String def = null;
      for (String part : spec.split(";", -1)) {
        if (part.isEmpty())
          continue;
        int eq = part.indexOf('=');
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
      props.putIfAbsent("sticky", "tx"); // tx|session|statement
      props.putIfAbsent("verbosity", "0"); // 0..5 (0 = off)
      return new Parsed(def, map, props);
    }
  }

  // ---------- Connection ----------
  static final class RoutedConnection implements Connection {
    private final Parsed cfg;
    private Connection active;
    private String activeLabel;
    private boolean autoCommit = true;
    private boolean closed;

    RoutedConnection(Parsed p) {
      this.cfg = p;
    }

    private String sticky() {
      String v = cfg.props.get("sticky");
      return v == null ? "tx" : v;
    }

    private int verbosity() {
      String v = cfg.props.get("verbosity");
      try {
        return v == null ? 0 : Integer.parseInt(v);
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    private void logRoute(String stage, String label, String sql) {
      // Always log; RichLogback's verbosity decides what actually appears.
      if (log.isDebugEnabled()) {
        String active = (activeLabel == null ? "-" : activeLabel);
        log.debug("{} sticky={} active={} -> ds={}", stage, sticky(), active, label);
        if (log.isTraceEnabled() && sql != null) {
          // Full SQL (hint stripped) only at TRACE to avoid noise
          log.trace("sql={}", stripHint(sql));
        }
      }
    }

    private static String truncate(String s, int n) {
      return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private Connection backend(String label) throws SQLException {
      log.trace("");
      String jdbcUrl = cfg.labelToUrl.get(label);
      if (jdbcUrl == null)
        throw new SQLException("Unknown backend label: " + label);
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
      Connection c = DriverManager.getConnection(jdbcUrl);
      if (!autoCommit)
        c.setAutoCommit(false);
      return c;
    }

    private void ensureBackendForTx(String label) throws SQLException {
      String mode = sticky();
      if ("statement".equalsIgnoreCase(mode))
        return; // per-statement handled outside
      if (active == null) {
        active = backend(label);
        activeLabel = label;
        logRoute("open-backend", label, "/*open*/");
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
        logRoute("switch-backend(session)", label, "/*switch*/");
        return;
      }

      // sticky=tx
      if (autoCommit) {
        try {
          active.close();
        } catch (SQLException ignore) {
        }
        active = backend(label);
        activeLabel = label;
        logRoute("switch-backend(autocommit)", label, "/*switch*/");
      } else {
        throw new SQLException(
            "Cannot switch backend within an open transaction (sticky=tx). COMMIT/ROLLBACK or set sticky=session/statement.");
      }
    }

    Connection openBackendForStatement(String label) throws SQLException {
      Connection c = backend(label);
      logRoute("open-backend(stmt)", label, "/*stmt-open*/");
      return c;
    }

    // ----- hint helpers -----
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

    // ----- Statement factories -----
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

    // ----- PreparedStatement factories -----
    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
      return ps(sql, (c, body) -> c.prepareStatement(body));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
      return ps(sql, (c, body) -> c.prepareStatement(body, autoGeneratedKeys));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
      return ps(sql, (c, body) -> c.prepareStatement(body, columnIndexes));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
      return ps(sql, (c, body) -> c.prepareStatement(body, columnNames));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
      return ps(sql, (c, body) -> c.prepareStatement(body, resultSetType, resultSetConcurrency));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      return ps(sql, (c, body) -> c.prepareStatement(body, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    // ----- CallableStatement factories -----
    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
      return cs(sql, (c, body) -> c.prepareCall(body));
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
      return cs(sql, (c, body) -> c.prepareCall(body, resultSetType, resultSetConcurrency));
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
        int resultSetHoldability) throws SQLException {
      return cs(sql, (c, body) -> c.prepareCall(body, resultSetType, resultSetConcurrency, resultSetHoldability));
    }

    // ----- shared factory helpers -----
    private interface PSFactory {
      PreparedStatement make(Connection c, String body) throws SQLException;
    }

    private PreparedStatement ps(String sql, PSFactory f) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        logRoute("prepareStatement(stmt)", label, body);
        return autoClose(f.make(c, body), c);
      }
      ensureBackendForTx(label);
      logRoute("prepareStatement", label, body);
      return f.make(active, body);
    }

    private interface CSFactory {
      CallableStatement make(Connection c, String body) throws SQLException;
    }

    private CallableStatement cs(String sql, CSFactory f) throws SQLException {
      String label = parseHintLabel(sql, cfg.defaultLabel);
      String body = stripHint(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(label);
        logRoute("prepareCall(stmt)", label, body);
        return autoClose(f.make(c, body), c);
      }
      ensureBackendForTx(label);
      logRoute("prepareCall", label, body);
      return f.make(active, body);
    }

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

    // ----- Tx / meta / boilerplate -----
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

    private Connection metaConn() throws SQLException {
      if (active != null)
        return active;
      active = backend(cfg.defaultLabel);
      activeLabel = cfg.defaultLabel;
      logRoute("open-backend(meta)", activeLabel, "/*meta*/");
      return active;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
      return metaConn().getMetaData();
    }

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
  }

  // ---------- Statement ----------
  static final class RoutedStatement implements Statement {
    private final RoutedConnection parent;
    private final int rsType;
    private final int rsConcurrency;
    private final int rsHoldability;

    // delegate state for DBeaver execute()/getResultSet() flow
    private Statement delegateStmt;
    private Connection delegateConn; // only for sticky=statement
    private ResultSet lastResultSet;
    private int lastUpdateCount = -1;

    RoutedStatement(RoutedConnection p) {
      this(p, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    RoutedStatement(RoutedConnection p, int type, int concurrency, int holdability) {
      this.parent = p;
      this.rsType = type;
      this.rsConcurrency = concurrency;
      this.rsHoldability = holdability;
    }

    private Statement createStmtSafe(Connection c) throws SQLException {
      // Try full signature; on driver complaints (e.g., Oracle holdability), fall
      // back progressively.
      try {
        return c.createStatement(rsType, rsConcurrency, rsHoldability);
      } catch (SQLFeatureNotSupportedException | SQLSyntaxErrorException | SQLDataException e) {
        try {
          return c.createStatement(rsType, rsConcurrency);
        } catch (SQLFeatureNotSupportedException e2) {
          return c.createStatement();
        }
      }
    }

    private void clearLast() {
      try {
        if (lastResultSet != null)
          lastResultSet.close();
      } catch (SQLException ignore) {
      }
      lastResultSet = null;
      lastUpdateCount = -1;
    }

    private void closeDelegate() {
      clearLast();
      try {
        if (delegateStmt != null)
          delegateStmt.close();
      } catch (SQLException ignore) {
      }
      delegateStmt = null;
      try {
        if (delegateConn != null)
          delegateConn.close();
      } catch (SQLException ignore) {
      }
      delegateConn = null;
    }

    private Connection routeOpen(String sql) throws SQLException {
      String label = MjdbcDriver.RoutedConnection.parseHintLabel(sql, parent.cfg.defaultLabel);
      if ("statement".equalsIgnoreCase(parent.sticky())) {
        parent.logRoute("stmt-open(stmt)", label, sql);
        Connection c = parent.openBackendForStatement(label);
        delegateConn = c;
        return c;
      }
      parent.ensureBackendForTx(label);
      parent.logRoute("stmt-open", label, sql);
      return parent.active;
    }

    private String body(String sql) {
      return MjdbcDriver.RoutedConnection.stripHint(sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
      closeDelegate(); // new execution invalidates previous delegate
      Connection c = routeOpen(sql);
      delegateStmt = createStmtSafe(c);
      boolean hasRS = delegateStmt.execute(body(sql));
      if (hasRS) {
        lastResultSet = delegateStmt.getResultSet();
        lastUpdateCount = -1;
      } else {
        lastResultSet = null;
        lastUpdateCount = delegateStmt.getUpdateCount();
      }
      return hasRS;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
      closeDelegate();
      Connection c = routeOpen(sql);
      delegateStmt = createStmtSafe(c);
      lastResultSet = delegateStmt.executeQuery(body(sql));
      lastUpdateCount = -1;
      return lastResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
      closeDelegate();
      Connection c = routeOpen(sql);
      delegateStmt = createStmtSafe(c);
      lastUpdateCount = delegateStmt.executeUpdate(body(sql));
      lastResultSet = null;
      return lastUpdateCount;
    }

    // ---- DBeaver relies on these after execute() ----
    @Override
    public ResultSet getResultSet() {
      return lastResultSet;
    }

    @Override
    public int getUpdateCount() {
      return lastUpdateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
      if (delegateStmt == null)
        return false;
      boolean more = delegateStmt.getMoreResults();
      if (more) {
        lastResultSet = delegateStmt.getResultSet();
        lastUpdateCount = -1;
      } else {
        lastResultSet = null;
        lastUpdateCount = delegateStmt.getUpdateCount();
      }
      return more;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
      if (delegateStmt == null)
        return false;
      boolean more = delegateStmt.getMoreResults(current);
      if (more) {
        lastResultSet = delegateStmt.getResultSet();
        lastUpdateCount = -1;
      } else {
        lastResultSet = null;
        lastUpdateCount = delegateStmt.getUpdateCount();
      }
      return more;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
      return delegateStmt == null ? null : delegateStmt.getGeneratedKeys();
    }

    // ---- lifecycle ----
    @Override
    public void close() {
      closeDelegate();
    }

    // ---- Minimal passthroughs / stubs ----
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
      try {
        if (delegateStmt != null)
          delegateStmt.cancel();
      } catch (SQLException ignore) {
      }
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
    public int getFetchDirection() {
      return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(int direction) {
    }

    @Override
    public int getFetchSize() {
      return 0;
    }

    @Override
    public void setFetchSize(int rows) {
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
    public int getResultSetHoldability() {
      return rsHoldability;
    }

    @Override
    public boolean isClosed() {
      return delegateStmt == null;
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

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
      boolean b = execute(sql);
      return b;
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
      boolean b = execute(sql);
      return b;
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
      boolean b = execute(sql);
      return b;
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
  }
}
