/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 *  conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.session.request.strategy;

import java.util.*;

import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.cypher.*;
import org.neo4j.ogm.cypher.function.FilterFunction;
import org.neo4j.ogm.cypher.query.AbstractRequest;
import org.neo4j.ogm.cypher.query.DefaultGraphModelRequest;
import org.neo4j.ogm.cypher.query.DefaultGraphRowListModelRequest;
import org.neo4j.ogm.exception.MissingOperatorException;
import org.neo4j.ogm.session.Utils;

/**
 * @author Vince Bickers
 * @author Luanne Misquitta
 */
public class VariableDepthQuery implements QueryStatements {

    @Override
    public AbstractRequest findOne(Long id, int depth) {
        int max = max(depth);
        int min = min(max);
        if (depth < 0) {
            return InfiniteDepthReadStrategy.findOne(id);
        }
        if (max > 0) {
            String qry = String.format("MATCH (n) WHERE id(n) = { id } WITH n MATCH p=(n)-[*%d..%d]-(m) RETURN p", min, max);
            return new DefaultGraphModelRequest(qry, Utils.map("id", id));
        } else {
            return DepthZeroReadStrategy.findOne(id);
        }
    }

    @Override
    public AbstractRequest findAll(Collection<Long> ids, int depth) {
        int max = max(depth);
        int min = min(max);
        if (depth < 0) {
            return InfiniteDepthReadStrategy.findAll(ids);
        }
        if (max > 0) {
            String qry=String.format("MATCH (n) WHERE id(n) in { ids } WITH n MATCH p=(n)-[*%d..%d]-(m) RETURN p", min, max);
            return new DefaultGraphModelRequest(qry, Utils.map("ids", ids));
        } else {
            return DepthZeroReadStrategy.findAll(ids);
        }
    }

    @Override
    public AbstractRequest findAllByType(String label, Collection<Long> ids, int depth) {
        int max = max(depth);
        int min = min(max);
        if (depth < 0) {
            return InfiniteDepthReadStrategy.findAllByLabel(label, ids);
        }
        if (max > 0) {
            String qry=String.format("MATCH (n:`%s`) WHERE id(n) in { ids } WITH n MATCH p=(n)-[*%d..%d]-(m) RETURN p", label, min, max);
            return new DefaultGraphModelRequest(qry, Utils.map("ids", ids));
        } else {
            return DepthZeroReadStrategy.findAllByLabel(label, ids);
        }
    }

    @Override
    public AbstractRequest findAll() {
        return new DefaultGraphModelRequest("MATCH p=()-->() RETURN p", Utils.map());
    }

    @Override
    public AbstractRequest findByType(String label, int depth) {
        int max = max(depth);
        int min = min(max);
        if (depth < 0) {
            return InfiniteDepthReadStrategy.findByLabel(label);
        }
        if (max > 0) {
            String qry = String.format("MATCH (n:`%s`) WITH n MATCH p=(n)-[*%d..%d]-(m) RETURN p", label, min, max);
            return new DefaultGraphModelRequest(qry, Utils.map());
        } else {
            return DepthZeroReadStrategy.findByLabel(label);
        }
    }

    @Override
    public AbstractRequest findByProperties(String label, Filters parameters, int depth) {
        int max = max(depth);
        int min = min(max);
        if (depth < 0) {
            return InfiniteDepthReadStrategy.findByProperties(label, parameters);
        }
        if (max > 0) {
            Map<String,Object> properties = new HashMap<>();
            StringBuilder query = constructQuery(label, parameters, properties);
            query.append(String.format("WITH n MATCH p=(n)-[*%d..%d]-(m) RETURN p, ID(n)",min,max));
            return new DefaultGraphRowListModelRequest(query.toString(), properties);
        } else {
            return DepthZeroReadStrategy.findByProperties(label, parameters);
        }
    }

    private static StringBuilder constructQuery(String label, Filters filters, Map<String, Object> properties) {
        Map<String, StringBuilder> matchClauses = new LinkedHashMap<>(); //All individual MATCH classes, grouped by node label
        Map<String, String> matchClauseIdentifiers = new HashMap<>(); //Mapping of the node label to the identifier used in the query
        List<StringBuilder> relationshipClauses = new ArrayList<>(); //All relationship clauses
        int matchClauseId = 0;
        boolean noneOperatorEncountered = false;
        String nodeIdentifier="n";

        //Create a match required to support the node entity we're supposed to return
        createOrFetchMatchClause(label,nodeIdentifier,matchClauses);

        for (Filter filter : filters) {
            StringBuilder matchClause;
            if (filter.getBooleanOperator().equals(BooleanOperator.NONE)) {
                if (noneOperatorEncountered) {
                    throw new MissingOperatorException("BooleanOperator missing for filter with property name " + filter.getPropertyName() + ". Only the first filter may not specify the BooleanOperator.");
                }
                noneOperatorEncountered = true;
            }
            if(filter.isNested()) {
                if(filter.getBooleanOperator().equals(BooleanOperator.OR)) {
                    throw new UnsupportedOperationException("OR is not supported for nested properties on an entity");
                }
                nodeIdentifier = "m" + matchClauseId; //Each nested filter produces a unique id for each type of node label
                if(filter.isNestedRelationshipEntity()) {
                    //There is no match clause for a relationship entity, instead, we append parameters to the relationship
                    matchClause = constructRelationshipClause(filter, nodeIdentifier);
                    matchClauses.put(filter.getRelationshipType(),matchClause);
                    nodeIdentifier = "r"; //TODO this implies support for querying by one relationship entity only
                }
                else {
                    if(matchClauseIdentifiers.containsKey(filter.getNestedEntityTypeLabel())) {
                        //Use the node identifier already created for nodes with this label
                        nodeIdentifier = matchClauseIdentifiers.get(filter.getNestedEntityTypeLabel());
                    }
                    else {
                        //This node identifier  has not been constructed yet, so do so and also construct its' relationship clause
                        matchClauseIdentifiers.put(filter.getNestedEntityTypeLabel(),nodeIdentifier);
                        relationshipClauses.add(constructRelationshipClause(filter, nodeIdentifier));
                    }
                    matchClause = createOrFetchMatchClause(filter.getNestedEntityTypeLabel(), nodeIdentifier, matchClauses);
                }
                matchClauseId++;
            }
            else {
                //If the filter is not nested, it belongs to the node we're returning
                nodeIdentifier = "n";
                matchClause = createOrFetchMatchClause(label, nodeIdentifier, matchClauses);
            }
            matchClause.append(filter.toCypher(nodeIdentifier, matchClause.indexOf(" WHERE ") == -1));
            properties.putAll(filter.parameters());
        }
        //Construct the query by appending all match clauses followed by all relationship clauses
        return buildQuery(matchClauses, relationshipClauses);
    }

    private static StringBuilder buildQuery(Map<String, StringBuilder> matchClauses, List<StringBuilder> relationshipClauses) {
        StringBuilder query = new StringBuilder();
        for(StringBuilder matchClause : matchClauses.values()) {
            query.append(matchClause);
        }
        for(StringBuilder relationshipClause : relationshipClauses) {
            query.append(relationshipClause);
        }
        return query;
    }

    /**
     * Construct a relationship match clause for a filter
     * @param filter the {@link Filter}
     * @param nodeIdentifier the node identifier used for the other node of the relationship
     * @return the relationship clause
     */
    private static StringBuilder constructRelationshipClause(Filter filter, String nodeIdentifier) {
        StringBuilder relationshipMatch;
        relationshipMatch = new StringBuilder("MATCH (n)");
        if(filter.getRelationshipDirection().equals(Relationship.INCOMING)) {
			relationshipMatch.append("<");
		}
        relationshipMatch.append(String.format("-[%s:`%s`]-", filter.isNestedRelationshipEntity() ? "r" : "", filter.getRelationshipType()));
        if(filter.getRelationshipDirection().equals(Relationship.OUTGOING)) {
			relationshipMatch.append(">");
		}
        relationshipMatch.append(String.format("(%s) ", nodeIdentifier));
        return relationshipMatch;
    }

    /**
     * Create of fetch an existing match clause for a node with a given label and node identifier
     * @param label the label of the node
     * @param nodeIdentifier the node identifier
     * @param matchClauses Map of existing match clauses, with key=node label and value=match clause
     * @return the match clause
     */
    private static StringBuilder createOrFetchMatchClause(String label, String nodeIdentifier, Map<String,StringBuilder> matchClauses) {
        if(matchClauses.containsKey(label)) {
            return matchClauses.get(label);
        }
        StringBuilder matchClause = new StringBuilder();

        matchClause.append(String.format("MATCH (%s:`%s`) ", nodeIdentifier, label));
        matchClauses.put(label, matchClause);
        return matchClause;
    }

    private int min(int depth) {
        return Math.min(0, depth);
    }

    private int max(int depth) {
        return Math.max(0, depth);
    }

    private static class DepthZeroReadStrategy {

        public static DefaultGraphModelRequest findOne(Long id) {
            return new DefaultGraphModelRequest("MATCH (n) WHERE id(n) = { id } RETURN n", Utils.map("id", id));
        }

        public static DefaultGraphModelRequest findAll(Collection<Long> ids) {
            return new DefaultGraphModelRequest("MATCH (n) WHERE id(n) in { ids } RETURN n", Utils.map("ids", ids));
        }

        public static DefaultGraphModelRequest findAllByLabel(String label, Collection<Long> ids) {
            return new DefaultGraphModelRequest(String.format("MATCH (n:`%s`) WHERE id(n) in { ids } RETURN n",label), Utils.map("ids", ids));
        }


        public static DefaultGraphModelRequest findByLabel(String label) {
            return new DefaultGraphModelRequest(String.format("MATCH (n:`%s`) RETURN n", label), Utils.map());
        }

        public static DefaultGraphModelRequest findByProperties(String label, Filters parameters) {
            Map<String,Object> properties = new HashMap<>();
            StringBuilder query = constructQuery(label, parameters, properties);
            query.append("RETURN n");
            return new DefaultGraphModelRequest(query.toString(), properties);
        }

    }

    private static class InfiniteDepthReadStrategy {

        public static DefaultGraphModelRequest findOne(Long id) {
            return new DefaultGraphModelRequest("MATCH (n) WHERE id(n) = { id } WITH n MATCH p=(n)-[*0..]-(m) RETURN p", Utils.map("id", id));
        }

        public static DefaultGraphModelRequest findAll(Collection<Long> ids) {
            return new DefaultGraphModelRequest("MATCH (n) WHERE id(n) in { ids } WITH n MATCH p=(n)-[*0..]-(m) RETURN p", Utils.map("ids", ids));
        }

        public static DefaultGraphModelRequest findAllByLabel(String label, Collection<Long> ids) {
            return new DefaultGraphModelRequest(String.format("MATCH (n:`%s`) WHERE id(n) in { ids } WITH n MATCH p=(n)-[*0..]-(m) RETURN p",label), Utils.map("ids", ids));
        }

        public static DefaultGraphModelRequest findByLabel(String label) {
            return new DefaultGraphModelRequest(String.format("MATCH (n:`%s`) WITH n MATCH p=(n)-[*0..]-(m) RETURN p", label), Utils.map());
        }

        public static DefaultGraphRowListModelRequest findByProperties(String label, Filters parameters) {
            Map<String,Object> properties = new HashMap<>();
            StringBuilder query = constructQuery(label, parameters, properties);
            query.append(" WITH n MATCH p=(n)-[*0..]-(m) RETURN p, ID(n)");
            return new DefaultGraphRowListModelRequest(query.toString(), properties);
        }

    }
}
