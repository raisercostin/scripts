///usr/bin/env jbang "$0" "$@" & exit /b %ERRORLEVEL%
//DEPS org.slf4j:slf4j-api:2.0.13
//DEPS ch.qos.logback:logback-classic:1.5.6
//DEPS org.postgresql:postgresql:42.7.3
//DEPS com.oracle.database.jdbc:ojdbc11:23.4.0.24.05
//DEPS io.trino:trino-jdbc:476
package org.raisercostin.mjdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/*
JDBC URL format:
  jdbc:mjdbc:
    default=<dsKey>;
    sticky=tx|session|statement;
    verbosity=0..5;
    route=hint,regex,default;
    drivers=<fqcn1>,<fqcn2>,...;
    <dsKey>=<jdbc-url>;
    <dsKey>=<jdbc-url>;
    ...

Examples:
  jdbc:mjdbc:
    default=pg;
    sticky=statement;
    verbosity=3;
    route=hint,regex,default;
    drivers=org.postgresql.Driver,oracle.jdbc.OracleDriver;
    pg=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=secret;
    ora=jdbc:oracle:thin:@//host:1521/XEPDB1?user=u&password=p

Routing strategies (in order):
- hint   : leading comment / * ds:<dsKey> * / picks the datasource key and is stripped.
- regex  : config-driven regex rules route by SQL content (see route.regex.N below).
- default: fallback to `default=<dsKey>`.

Regex rules:
  route.regex.1=pg:/\\bFROM\\s+pg_\\w+/i
  route.regex.2=ora:/\\bFROM\\s+DUAL\\b/
*/
public final class MjdbcDriver implements java.sql.Driver {
  private static final Logger LOG = LoggerFactory.getLogger("mjdbc");

  static {
    try {
      DriverManager.registerDriver(new MjdbcDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      LOG.info("mjdbc driver loaded.");
      LOG.info("Usage: test <jdbc-url> <sql1> <sql2> ...");
      LOG.info("Example URL: jdbc:mjdbc:default=pg;sticky=statement;verbosity=3;route=hint,regex,default;drivers=org.postgresql.Driver,oracle.jdbc.OracleDriver;pg=jdbc:postgresql://...;ora=jdbc:oracle:thin:@//...?");
      return;
    }

    if ("test".equalsIgnoreCase(args[0])) {
      if (args.length < 3) {
        LOG.error("Usage: test <jdbc-url> <sql1> <sql2> ...");
        return;
      }
      String jdbcUrl = args[1];
      Class.forName("org.raisercostin.mjdbc.MjdbcDriver");
      try (Connection conn = DriverManager.getConnection(jdbcUrl);
           Statement st = conn.createStatement()) {
        for (int i = 2; i < args.length; i++) {
          String sql = args[i];
          LOG.info("Executing: {}", sql);
          boolean hasResult = st.execute(sql);
          if (hasResult) {
            try (ResultSet rs = st.getResultSet()) {
              int cols = rs.getMetaData().getColumnCount();
              // Print header
              StringBuilder header = new StringBuilder();
              for (int c = 1; c <= cols; c++) {
                if (c > 1) header.append(" | ");
                header.append(rs.getMetaData().getColumnLabel(c));
              }
              LOG.info("Header: {}", header);

              // Print rows
              while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int c = 1; c <= cols; c++) {
                  if (c > 1) row.append(" | ");
                  row.append(rs.getString(c));
                }
                LOG.info("Row: {}", row);
              }
            }
          } else {
            int updateCount = st.getUpdateCount();
            LOG.info("Update count: {}", updateCount);
          }
        }
      }
      return;
    }

    LOG.info("Unknown command: {}. Exiting.", args[0]);
  }



  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url))
      return null;
    Parsed p = Parsed.parse(url);

    // Optional: configure global logback via your helper if present.
    try {
      int verbosity = Integer.parseInt(p.props.getOrDefault("verbosity", "0"));
      Class<?> cls = Class.forName("com.namekis.utils.RichLogback");
      cls.getMethod("configureLogbackByVerbosity", int.class, boolean.class, boolean.class).invoke(null, verbosity,
          false, true);
      LOG.info("mjdbc logging configured via RichLogback (verbosity={})", verbosity);
    } catch (Throwable ignore) {
      // Helper not present or already configured elsewhere. Proceed.
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
    return 5;
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
    final String defaultKey;
    final Map<String, String> keyToUrl;
    final Map<String, String> props;

    private Parsed(String def, Map<String, String> map, Map<String, String> props) {
      this.defaultKey = def;
      this.keyToUrl = map;
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
        throw new SQLException("default key ["+def+"] missing or unknown. Available ones: "+map.keySet());
      props.putIfAbsent("sticky", "tx"); // tx|session|statement
      props.putIfAbsent("verbosity", "0"); // 0..5
      props.putIfAbsent("route", "hint,regex,default");
      props.putIfAbsent("drivers", ""); // comma-separated FQCNs
      return new Parsed(def, map, props);
    }
  }

  // ---------- Routing strategies ----------
  interface RoutingStrategy {
    /** return chosen datasource key or null if undecided */
    String selectKey(String sql, String defaultKey, Map<String, String> props) throws SQLException;

    /** optionally transform SQL (e.g., strip hint) when strategy matched */
    default String transformSql(String sql) {
      return sql;
    }
  }

  static final class HintRoutingStrategy implements RoutingStrategy {
    @Override
    public String selectKey(String sql, String def, Map<String, String> props) throws SQLException {
      String s = sql.trim();
      if (!s.startsWith("/*ds:"))
        return null;
      int e = s.indexOf("*/");
      if (e < 0)
        throw new SQLException("Malformed routing hint. Expected closing */");
      String inside = s.substring(5, e).trim();
      if (inside.isEmpty())
        throw new SQLException("Empty datasource key in hint.");
      return inside;
    }

    @Override
    public String transformSql(String sql) {
      String s = sql.trim();
      if (!s.startsWith("/*ds:"))
        return sql;
      int e = s.indexOf("*/");
      return (e > 0) ? s.substring(e + 2).trim() : sql;
    }
  }

  static final class RegexRoutingStrategy implements RoutingStrategy {
    static final class Rule {
      final Pattern p;
      final String key;

      Rule(Pattern p, String k) {
        this.p = p;
        this.key = k;
      }
    }

    final List<Rule> rules = new ArrayList<>();

    RegexRoutingStrategy(Map<String, String> props) {
      props.forEach((k, v) -> {
        if (k.startsWith("route.regex.")) {
          int colon = v.indexOf(':');
          if (colon <= 0)
            return;
          String key = v.substring(0, colon).trim();
          String pat = v.substring(colon + 1).trim();
          int flags = 0;
          if (pat.startsWith("/") && pat.lastIndexOf('/') > 0) {
            int last = pat.lastIndexOf('/');
            String body = pat.substring(1, last);
            String f = pat.substring(last + 1);
            if (f.contains("i"))
              flags |= Pattern.CASE_INSENSITIVE;
            pat = body;
          }
          rules.add(new Rule(Pattern.compile(pat, flags), key));
        }
      });
    }

    @Override
    public String selectKey(String sql, String def, Map<String, String> props) {
      for (Rule r : rules)
        if (r.p.matcher(sql).find())
          return r.key;
      return null;
    }
  }

  static final class DefaultRoutingStrategy implements RoutingStrategy {
    @Override
    public String selectKey(String sql, String def, Map<String, String> props) {
      return def;
    }
  }

  // ---------- Connection ----------
  static final class RoutedConnection implements Connection {
    private final Parsed cfg;

    private Connection active;
    private String activeKey;
    private boolean autoCommit = true;
    private boolean closed;

    private final List<RoutingStrategy> strategies = new ArrayList<>();
    private final Logger connLog;
    private final String connId;
    private boolean driverClassesLoaded = false;

    RoutedConnection(Parsed p) {
      this.cfg = p;
      this.connId = UUID.randomUUID().toString().substring(0, 8);
      this.connLog = LoggerFactory.getLogger("mjdbc.conn." + connId);
      setPerConnectionLevelFromVerbosity();
      initStrategies();
    }

    private void initStrategies() {
      String order = cfg.props.getOrDefault("route", "hint,regex,default");
      for (String s : order.split(",")) {
        String st = s.trim().toLowerCase(Locale.ROOT);
        switch (st) {
        case "hint" -> strategies.add(new HintRoutingStrategy());
        case "regex" -> strategies.add(new RegexRoutingStrategy(cfg.props));
        case "default" -> strategies.add(new DefaultRoutingStrategy());
        default -> {
          /* ignore unknown */ }
        }
      }
      if (strategies.isEmpty())
        strategies.add(new DefaultRoutingStrategy());
    }

    private void setPerConnectionLevelFromVerbosity() {
      int v;
      try {
        v = Integer.parseInt(cfg.props.getOrDefault("verbosity", "0"));
      } catch (NumberFormatException e) {
        v = 0;
      }
      // Map 0..5 to a level for this connection logger only
      ch.qos.logback.classic.Level level = (v >= 4) ? ch.qos.logback.classic.Level.TRACE
          : (v == 3) ? ch.qos.logback.classic.Level.DEBUG
              : (v >= 1) ? ch.qos.logback.classic.Level.INFO : ch.qos.logback.classic.Level.WARN;
      if (connLog instanceof ch.qos.logback.classic.Logger lb) {
        lb.setLevel(level);
        lb.setAdditive(true); // keep global appenders configured by RichLogback
      }
    }

    private String sticky() {
      String v = cfg.props.get("sticky");
      return v == null ? "tx" : v;
    }

    private void ensureDriverClassesLoadedOnce() {
      if (driverClassesLoaded)
        return;
      String list = cfg.props.getOrDefault("drivers", "").trim();
      if (!list.isEmpty()) {
        for (String cn : list.split(",")) {
          String cls = cn.trim();
          if (cls.isEmpty())
            continue;
          try {
            Class.forName(cls);
            connLog.debug("Loaded driver class: {}", cls);
          } catch (Throwable t) {
            connLog.warn("Driver class not found or failed to load: {}", cls);
          }
        }
      }
      driverClassesLoaded = true;
    }

    private Connection backend(String key) throws SQLException {
      ensureDriverClassesLoadedOnce();
      String jdbcUrl = cfg.keyToUrl.get(key);
      if (jdbcUrl == null)
        throw new SQLException("Unknown datasource key: " + key);
      Connection c = DriverManager.getConnection(jdbcUrl);
      if (!autoCommit)
        c.setAutoCommit(false);
      return c;
    }

    private record Choice(String key, String sql) {
    }

    private Choice chooseKeyAndSql(String sql) throws SQLException {
      String chosen = null;
      String transformed = sql;
      for (RoutingStrategy st : strategies) {
        String k = st.selectKey(sql, cfg.defaultKey, cfg.props);
        if (k != null) {
          chosen = k;
          transformed = st.transformSql(transformed);
          break;
        }
      }
      if (chosen == null)
        chosen = cfg.defaultKey;
      return new Choice(chosen, transformed);
    }

    private void logRoute(String stage, String key, String sql) {
      if (connLog.isDebugEnabled()) {
        connLog.debug("{} sticky={} active={} -> ds-key={}", stage, sticky(), activeKey == null ? "-" : activeKey, key);
        if (connLog.isTraceEnabled() && sql != null)
          connLog.trace("sql={}", sql);
      }
    }

    private void ensureBackendForTx(String key) throws SQLException {
      String mode = sticky();
      if ("statement".equalsIgnoreCase(mode))
        return; // handled per invocation
      if (active == null) {
        active = backend(key);
        activeKey = key;
        logRoute("open-backend", key, "/*open*/");
        return;
      }
      if (Objects.equals(activeKey, key))
        return;

      if ("session".equalsIgnoreCase(mode)) {
        try {
          active.close();
        } catch (SQLException ignore) {
        }
        active = backend(key);
        activeKey = key;
        logRoute("switch-backend(session)", key, "/*switch*/");
        return;
      }

      if (autoCommit) {
        try {
          active.close();
        } catch (SQLException ignore) {
        }
        active = backend(key);
        activeKey = key;
        logRoute("switch-backend(autocommit)", key, "/*switch*/");
      } else {
        throw new SQLException(
            "Cannot switch datasource within an open transaction (sticky=tx). COMMIT/ROLLBACK or set sticky=session/statement.");
      }
    }

    Connection openBackendForStatement(String key) throws SQLException {
      Connection c = backend(key);
      logRoute("open-backend(stmt)", key, "/*stmt-open*/");
      return c;
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

    // ---------- CallableStatement factories ----------
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

    // ---------- shared factory helpers ----------
    private interface PSFactory {
      PreparedStatement make(Connection c, String body) throws SQLException;
    }

    private PreparedStatement ps(String sql, PSFactory f) throws SQLException {
      Choice ch = chooseKeyAndSql(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(ch.key);
        logRoute("prepareStatement(stmt)", ch.key, ch.sql);
        return autoClose(f.make(c, ch.sql), c);
      }
      ensureBackendForTx(ch.key);
      logRoute("prepareStatement", ch.key, ch.sql);
      return f.make(active, ch.sql);
    }

    private interface CSFactory {
      CallableStatement make(Connection c, String body) throws SQLException;
    }

    private CallableStatement cs(String sql, CSFactory f) throws SQLException {
      Choice ch = chooseKeyAndSql(sql);
      if ("statement".equalsIgnoreCase(sticky())) {
        Connection c = openBackendForStatement(ch.key);
        logRoute("prepareCall(stmt)", ch.key, ch.sql);
        return autoClose(f.make(c, ch.sql), c);
      }
      ensureBackendForTx(ch.key);
      logRoute("prepareCall", ch.key, ch.sql);
      return f.make(active, ch.sql);
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

    // ---------- Tx / meta / boilerplate ----------
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
      active = backend(cfg.defaultKey);
      activeKey = cfg.defaultKey;
      logRoute("open-backend(meta)", activeKey, "/*meta*/");
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
      MjdbcDriver.RoutedConnection.Choice ch = parent.chooseKeyAndSql(sql);
      String key = ch.key();
      String body = ch.sql();
      if ("statement".equalsIgnoreCase(parent.sticky())) {
        parent.logRoute("stmt-open(stmt)", key, body);
        delegateConn = parent.openBackendForStatement(key);
        return delegateConn;
      }
      parent.ensureBackendForTx(key);
      parent.logRoute("stmt-open", key, body);
      return parent.active;
    }

    private String body(String sql) throws SQLException {
      return parent.chooseKeyAndSql(sql).sql(); // keep single transform path
    }

    @Override
    public boolean execute(String sql) throws SQLException {
      closeDelegate();
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

    // ---- DBeaver flow helpers ----
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
