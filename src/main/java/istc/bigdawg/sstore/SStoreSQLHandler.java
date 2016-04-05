package istc.bigdawg.sstore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import istc.bigdawg.BDConstants.Shim;
import istc.bigdawg.catalog.CatalogViewer;
import istc.bigdawg.query.ConnectionInfo;
import istc.bigdawg.query.DBHandler;
import istc.bigdawg.query.QueryClient;
import istc.bigdawg.utils.LogUtils;
import istc.bigdawg.utils.StackTrace;

public class SStoreSQLHandler implements DBHandler {

    private static Logger log = Logger.getLogger(SStoreSQLHandler.class.getName());
    // private static int defaultSchemaServerDBID =
    // BigDawgConfigProperties.INSTANCE.getSStoreSchemaServerDBID();
    private Connection con = null;
    private ConnectionInfo conInfo = null;
    private Statement st = null;
    private PreparedStatement preparedSt = null;
    private ResultSet rs = null;

    public SStoreSQLHandler(int dbId) throws Exception {
	try {
	    this.conInfo = CatalogViewer.getConnection(dbId);
	} catch (Exception e) {
	    String msg = "Catalog chosen connection: " + conInfo.getHost() + " " + conInfo.getPort() + " "
		    + conInfo.getUser() + " " + conInfo.getPassword() + ".";
	    log.error(msg);
	    e.printStackTrace();
	    throw e;
	}
    }

    public SStoreSQLHandler(SStoreSQLConnectionInfo conInfo) {
	try {
		Class.forName("org.voltdb.jdbc.Driver");
	} catch (ClassNotFoundException ex) {
		ex.printStackTrace();
		log.error("SStore jdbc driver is not in the CLASSPATH -> "
				+ ex.getMessage() + " " + StackTrace.getFullStackTrace(ex),
				ex);
		throw new RuntimeException(ex.getMessage());
	}
	this.conInfo = conInfo;
    }

    public SStoreSQLHandler() {
	String msg = "Default handler. SStoreSQL parameters from a file.";
	log.info(msg);
    }

    /**
     * Establish connection to SStoreSQL for this instance.
     * 
     * @throws SQLException
     *             if could not establish a connection
     */
    private Connection getConnection() throws SQLException {
	if (con == null) {
	    if (conInfo != null) {
		try {
		    con = getConnection(conInfo);
		} catch (SQLException e) {
		    e.printStackTrace();
		    log.error(e.getMessage() + " Could not connect to SStoreSQL database using: " + conInfo.toString(),
			    e);
		    throw e;
		}
	    } else {
		con = SStoreSQLInstance.getConnection();
	    }
	}
	return con;
    }

    public static Connection getConnection(ConnectionInfo conInfo) throws SQLException {
	Connection con;
	String url = conInfo.getUrl();
	String user = conInfo.getUser();
	String password = conInfo.getPassword();
	try {
	    con = DriverManager.getConnection(url, user, password);
	} catch (SQLException e) {
	    String msg = "Could not connect to the SStoreSQL instance: Url: " + url + " User: " + user + " Password: "
		    + password;
	    log.error(msg);
	    e.printStackTrace();
	    throw e;
	}
	return con;
    }

    public class QueryResult {
	private List<List<String>> rows;
	private List<String> types;
	private List<String> colNames;

	/**
	 * @return the rows
	 */
	public List<List<String>> getRows() {
	    return rows;
	}

	/**
	 * @return the types
	 */
	public List<String> getTypes() {
	    return types;
	}

	/**
	 * @return the colNames
	 */
	public List<String> getColNames() {
	    return colNames;
	}

	/**
	 * @param rows
	 * @param types
	 * @param colNames
	 */
	public QueryResult(List<List<String>> rows, List<String> types, List<String> colNames) {
	    super();
	    this.rows = rows;
	    this.types = types;
	    this.colNames = colNames;
	}

    }

    @Override
    public Response executeQuery(String queryString) {
	long lStartTime = System.nanoTime();
	QueryResult queryResult = null;
	try {
	    queryResult = executeQuerySStoreSQL(queryString);
	} catch (SQLException e) {
	    return Response.status(500)
		    .entity("Problem with query execution in SSToreSQL: " + e.getMessage() + "; query: " + queryString)
		    .build();
	    // return "Problem with query execution in SStoreSQL: " +
	    // queryString;
	}
	String messageQuery = "SSToreSQL query execution time milliseconds: "
		+ (System.nanoTime() - lStartTime) / 1000000 + ",";
	log.info(messageQuery);

	lStartTime = System.nanoTime();

	String out = "";
	for (String name : queryResult.getColNames()) {
	    out = out + "\t" + name;
	}
	out = out + "\n";
	Integer rowCounter = 1;
	for (List<String> row : queryResult.getRows()) {
	    out = out + rowCounter.toString() + ".";
	    for (String s : row) {
		out = out + "\t" + s;
	    }
	    out = out + "\n";
	    rowCounter += 1;
	}

	String messageTABLE = "format TABLE Java time milliseconds: " + (System.nanoTime() - lStartTime) / 1000000
		+ ",";
	log.info(messageTABLE);

	return Response.status(200).entity(out).build();
    }

    /**
     * Clean resource after a query/statement was executed in SStoreSQL.
     * 
     * @throws SQLException
     */
    private void cleanSStoreSQLResources() throws SQLException {
	if (rs != null) {
	    rs.close();
	    rs = null;
	}
	if (st != null) {
	    st.close();
	    st = null;
	}
	if (preparedSt != null) {
	    preparedSt.close();
	    preparedSt = null;
	}
	if (con != null) {
	    con.close();
	    con = null;
	}
    }

    /**
     * It executes the query and releases the resources at the end.
     * 
     * @param query
     * @return #QueryResult
     * @throws SQLException
     */
    public QueryResult executeQuerySStoreSQL(final String query) throws SQLException {
	try {
	    this.getConnection();

	    log.debug("\n\nquery: " + LogUtils.replace(query) + "");
	    if (this.conInfo != null) {
		log.debug("ConnectionInfo: " + this.conInfo.toString() + "\n");
	    }

	    st = con.createStatement();
	    rs = st.executeQuery(query);

	    ResultSetMetaData rsmd = rs.getMetaData();
	    List<String> colNames = getColumnNames(rsmd);
	    List<String> types = getColumnTypes(rsmd);
	    List<List<String>> rows = getRows(rs);
	    return new QueryResult(rows, types, colNames);
	} catch (SQLException ex) {
	    Logger lgr = Logger.getLogger(QueryClient.class.getName());
	    // ex.printStackTrace();
	    lgr.log(Level.ERROR, ex.getMessage() + "; query: " + LogUtils.replace(query), ex);
	    throw ex;
	} finally {
	    try {
		this.cleanSStoreSQLResources();
	    } catch (SQLException ex) {
		Logger lgr = Logger.getLogger(QueryClient.class.getName());
		// ex.printStackTrace();
		lgr.log(Level.INFO, ex.getMessage() + "; query: " + LogUtils.replace(query), ex);
		throw ex;
	    }
	}
    }

    public static List<List<String>> getRows(final ResultSet rs) throws SQLException {
	if (rs == null) {
	    return null;
	}
	List<List<String>> rows = new ArrayList<>();
	try {
	    ResultSetMetaData rsmd = rs.getMetaData();
	    int NumOfCol = rsmd.getColumnCount();
	    while (rs.next()) {
		List<String> current_row = new ArrayList<String>();
		for (int i = 1; i <= NumOfCol; i++) {
		    Object value = rs.getObject(i);
		    if (value == null) {
			current_row.add("null");
		    } else {
			current_row.add(value.toString());
		    }
		}
		rows.add(current_row);
	    }
	    return rows;
	} catch (SQLException e) {
	    throw e;
	}
    }

    public static List<String> getColumnNames(final ResultSetMetaData rsmd) throws SQLException {
	List<String> columnNames = new ArrayList<String>();
	for (int i = 1; i <= rsmd.getColumnCount(); ++i) {
	    columnNames.add(rsmd.getColumnLabel(i));
	}
	return columnNames;
    }

    public static List<String> getColumnTypes(final ResultSetMetaData rsmd) throws SQLException {
	List<String> columnTypes = new ArrayList<String>();
	for (int i = 1; i <= rsmd.getColumnCount(); ++i) {
	    columnTypes.add(rsmd.getColumnTypeName(i));
	}
	return columnTypes;
    }

    @Override
    public Shim getShim() {
	return Shim.SSTORESQLRELATION;
    }

    /**
     * Get metadata about columns (right now, just has column name, data type) for a
     * table in SStoreQL.
     * 
     * @param conInfo
     * @param tableName
     * @return map column name to column meta data
     * @throws SQLException
     *             if the data extraction from SStoreSQL failed
     */
    public SStoreSQLTableMetaData getColumnsMetaData(String tableName) throws SQLException {
	try {
	    this.getConnection();
	    try {
		st = con.createStatement();
		log.debug("replace double quotes (\") with signle quotes in the query to log it in SStoreSQL: "
			+ st.toString().replace("'", "\""));
	    } catch (SQLException e) {
		e.printStackTrace();
		log.error("SStoreSQLHandler, the query preparation failed. " + e.getMessage() + " "
			+ StackTrace.getFullStackTrace(e));
		throw e;
	    }
	    ResultSet resultSet = st.executeQuery("select * from " + tableName + " limit 1");
	    Map<String, SStoreSQLColumnMetaData> columnsMap = new HashMap<>();
	    List<SStoreSQLColumnMetaData> columnsOrdered = new ArrayList<>();
	    ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
	    for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
		SStoreSQLColumnMetaData columnMetaData = new SStoreSQLColumnMetaData(resultSetMetaData.getColumnName(i),
			resultSetMetaData.getColumnTypeName(i), false);
		columnsMap.put(resultSetMetaData.getColumnName(i), columnMetaData);
		columnsOrdered.add(columnMetaData);
	    }
	    
	    return new SStoreSQLTableMetaData(columnsMap, columnsOrdered);
	} finally {
	    try {
		this.cleanSStoreSQLResources();
	    } catch (SQLException ex) {
		ex.printStackTrace();
		log.error(ex.getMessage() + "; conInfo: " + conInfo.toString() + "; table: " + tableName + " "
			+ StackTrace.getFullStackTrace(ex), ex);
		throw ex;
	    }
	}
    }

}
