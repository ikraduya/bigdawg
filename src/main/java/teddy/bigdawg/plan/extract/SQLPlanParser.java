package teddy.bigdawg.plan.extract;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import teddy.bigdawg.extract.logical.SQLParseLogical;
import teddy.bigdawg.extract.logical.SQLTableExpression;
import teddy.bigdawg.plan.SQLQueryPlan;
import teddy.bigdawg.plan.operators.Operator;
import teddy.bigdawg.plan.operators.OperatorFactory;
import teddy.bigdawg.util.SQLPrepareQuery;
import teddy.bigdawg.util.SQLUtilities;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import istc.bigdawg.postgresql.PostgreSQLHandler;
import istc.bigdawg.postgresql.PostgreSQLHandler.QueryResult;
import net.sf.jsqlparser.statement.select.WithItem;


// takes in a psql plan from running
// EXPLAIN (VERBOSE ON, FORMAT XML) SELECT ...
// Produces a set of operators and their source / destination schemas
// see codegen.ops for tree nodes

// first pass, just parse ops - arrange for bottom up analysis
// build one obj per SELECT block, build tree to link them together
// second pass - map back to schema

public class SQLPlanParser {
	
	// needed for type resolution
	//private DatabaseSingleton catalog;
	SQLQueryPlan queryPlan;
	
	boolean skipSort = false; 

    
	// sqlPlan passes in supplement info
	public SQLPlanParser(String xmlString, SQLQueryPlan sqlPlan) throws Exception {
	   //catalog = DatabaseSingleton.getInstance();
		queryPlan = sqlPlan;
//		System.out.println("Parsing xml String...");
		
		DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		InputSource is = new InputSource();
		is.setCharacterStream(new StringReader(xmlString));
		Document document = builder.parse(is);
		  
	    //Iterating through the nodes and extracting the data.
        NodeList nodeList = document.getDocumentElement().getChildNodes();
      
		// <explain>            
	    for (int i = 0; i < nodeList.getLength(); i++) {
            Node query = nodeList.item(i);
            // <query>
            if(query.getNodeName() == "Query") {
            	for(int j = 0; j < query.getChildNodes().getLength(); ++j) {
            		Node plan = query.getChildNodes().item(j);
        		    // <Plan>
            		if(plan.getNodeName() == "Plan") {
            			Operator root = parsePlanTail("main", plan, 0);
            			queryPlan.setRootNode(root); 

            			break;
            		}
            	}
            }
           
	    }
			
		    
	}
	
	public static SQLQueryPlan extract(String sql) throws Exception {

		String xml = sql.replace(".sql", ".xml");
		
		SQLPrepareQuery.generatePlan(sql, xml);
		
		// set up supplement
		String query = SQLPrepareQuery.readSQL(sql);
		SQLParseLogical parser = new SQLParseLogical(query);
		SQLQueryPlan queryPlan = parser.getSQLQueryPlan();
		
		// run parser
		SQLPlanParser p = new SQLPlanParser(xml, queryPlan);
		
		return queryPlan;
	}
	
	public static SQLQueryPlan extractDirect(PostgreSQLHandler psqlh, String query) throws Exception {

		String explainQuery = SQLPrepareQuery.generateExplainQueryString(query);
		String xmlString = psqlh.generatePostgreSQLQueryXML(explainQuery);
		
		// set up supplement
		SQLParseLogical parser = new SQLParseLogical(query);
		SQLQueryPlan queryPlan = parser.getSQLQueryPlan();
		
		// run parser
		SQLPlanParser p = new SQLPlanParser(xmlString, queryPlan);
		
		return queryPlan;
	}
	
	// parse a single <Plan>
	Operator parsePlanTail(String planName, Node node, int recursionLevel) throws Exception {
		
		if(node.getNodeName() != "Plan") {
			throw new Exception("Not parsing a valid plan node!");
		}

		
		List<String> sortKeys = new ArrayList<String>();
		List<String> outItems = new ArrayList<String>();
		Map<String, String> parameters = new HashMap<String, String>();
		String nodeType = null;
		String localPlan = planName;
		SQLTableExpression supplement = queryPlan.getSQLTableExpression(planName);

		
		NodeList children = node.getChildNodes();
	
		List<Operator> childOps = new ArrayList<Operator>();
		
		// if node type matches scan then we have reached a leaf in the query plan
		// scan might be seq, cte, index, etc.
		
		for(int j = 0; j < children.getLength(); ++j) {
					Node c = children.item(j);
		
					switch(c.getNodeName())  {
					
					case "Node-Type":
						nodeType = c.getTextContent();
						parameters.put("Node-Type", nodeType);
						break;

					case "Strategy":
						if(c.getTextContent().equals("Sorted") && nodeType.equals("Aggregate")) {
							skipSort = true;
						}
					
					case "Output":
						  NodeList output = c.getChildNodes();
						  for(int k = 0; k < output.getLength(); ++k) {
							  Node outExpr = output.item(k);
							  if(outExpr.getNodeName() == "Item") {
								  String s = SQLUtilities.removeOuterParens(outExpr.getTextContent());
								  outItems.add(s);
							  }
						  }
						  break;
					case "Sort-Key":
						  NodeList sortNodes = c.getChildNodes();
						  for(int k = 0; k < sortNodes.getLength(); ++k) {
							  Node outExpr = sortNodes.item(k);
							  if(outExpr.getNodeName() == "Item") {
								  String s = SQLUtilities.removeOuterParens(outExpr.getTextContent());
								  sortKeys.add(s);
							  }
						  }
						  break;
					case "Subplan-Name":
						localPlan = c.getTextContent(); // switch to new CTE
						localPlan = localPlan.substring(localPlan.indexOf(" ")+1);  // chop out "CTE " prefix
						supplement = queryPlan.getSQLTableExpression(localPlan);
						break;
					case "Plans":
						int r = recursionLevel+1;
						childOps = parsePlansTail(localPlan, c, r);
						break;
						
					default:
						parameters.put(c.getNodeName(), c.getTextContent()); // record it for later
					}

					
				} // end children for plan


				Operator op;
				if(nodeType.equals("Sort") && skipSort == true) { // skip sort associated with GroupAggregate
					op = childOps.get(0);
					skipSort = false;
				}
				else {
					op =  OperatorFactory.get(nodeType, parameters, outItems, sortKeys, childOps, queryPlan, supplement);
				}

//				System.out.println("Generated operator for " + op.getClass() + " with out schema " + op.getOutSchema().keySet());
				
				if(!localPlan.equals(planName)) { // we created a cte
					WithItem w = queryPlan.getWithItem(localPlan);

					op.setCTERootStatus(true);
					queryPlan.addPlan(localPlan, op);
				}
				
				return op;
	}
	
	
	
	// handle a <Plans> op, might return a list
	List<Operator> parsePlansTail(String planName, Node node, int recursionLevel) throws Exception {
		NodeList children = node.getChildNodes();
		List<Operator> childNodes = new ArrayList<Operator>();
		
		for(int i = 0; i < children.getLength(); ++i) {
			Node c = children.item(i);
			if(c.getNodeName() == "Plan") {
				Operator o = parsePlanTail(planName, c, recursionLevel+1);
				
				// only add children that are part of the main plan, not the CTEs which are accounted for in CTEScan
				if(!o.CTERoot()) {
					childNodes.add(o);
				}
			}
		}
		
		return childNodes;
	}

	
	public static String padLeft(String s, int n) {
		if(n > 0) {
			return String.format("%1$" + n + "s", s);  
		}
		 
		return s;
	}

	public static String padRight(String s, int n) {
		if(n > 0) {
		     return String.format("%1$-" + n + "s", s);  
		}
		return s;
		
	}
	
}

	