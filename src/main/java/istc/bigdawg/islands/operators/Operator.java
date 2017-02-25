package istc.bigdawg.islands.operators;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import istc.bigdawg.islands.DataObjectAttribute;
import istc.bigdawg.shims.OperatorQueryGenerator;

public interface Operator {
	
	/**
	 * This function indicate for the Executor whether this operator is the root of a sub-query that needs to be materialized
	 * @return
	 */
	public boolean isSubTree();
	public boolean isPruned();
	public boolean isCTERoot();
	public boolean isBlocking();
	public boolean isQueryRoot();
	public boolean isCopy();
	
	public void setSubTree(boolean t);
	public void prune(boolean p);
	public void setCTERoot(boolean b);
	public void setQueryRoot(boolean isQueryRoot);
	
	public String getSubTreeToken() throws Exception;
	public String getPruneToken() throws Exception;
	public Integer getPruneID();
	public Integer getBlockerID() throws Exception;
	
	
	public Operator getParent();
	public List<Operator> getChildren();
	
	public void setParent(Operator p);
	public void addChild(Operator aChild);
	public void addChilds(Collection<Operator> childs);
	
	public Map<String, DataObjectAttribute>  getOutSchema();
	
	/**
	 * get a list of all blocking operators among the offsprings of this operator  
	 * @return
	 */
	public List<Operator> getAllBlockers();
	
	/**
	 * Create a duplicate of this operator 
	 * @param addChild - add all child and progeny operators if true, none otherwise
	 * @return a duplicate of this operator
	 * @throws Exception
	 */
	public abstract Operator duplicate(boolean addChild) throws Exception;
	
	/**
	 * The entries returned by this function should be a map between an object's alias and its original name
	 * @return
	 * @throws Exception
	 */
	public Map<String, String> getDataObjectAliasesOrNames() throws Exception;
	
	/**
	 * This function is used for Signature construction. 
	 * Implementing it is necessary only when Common Table Expression (WITH) is supported.  
	 * @param entry
	 * @throws Exception
	 */
	public void removeCTEEntriesFromObjectToExpressionMapping(Map<String, Set<String>> entry) throws Exception;
	
	/**
	 * This function delivers parenthesize and simplified representation of the operator tree. 
	 * @param isRoot
	 * @return
	 * @throws Exception
	 */
	public String getTreeRepresentation(boolean isRoot) throws Exception;
	
	/**
	 * The entries should be aliases or original names of an object map to a set of all join predicates that contain references to the object  
	 * @return
	 * @throws Exception
	 */
	public Map<String, Set<String>> getObjectToExpressionMappingForSignature() throws Exception;
	
	/**
	 * Part of the visitor pattern that allows the generators to translate the operator tree into executable queries
	 * @param OperatorQueryGenerator
	 * @throws Exception
	 */
	public void accept(OperatorQueryGenerator generator) throws Exception;
	
}
