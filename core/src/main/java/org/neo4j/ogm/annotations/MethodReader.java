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

package org.neo4j.ogm.annotations;


import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.metadata.AnnotationInfo;
import org.neo4j.ogm.metadata.ClassInfo;
import org.neo4j.ogm.metadata.MethodInfo;
import org.neo4j.ogm.metadata.ObjectAnnotations;

/**
 * @author Adam George
 * @author Luanne Misquitta
 */
public class MethodReader implements RelationalReader, PropertyReader {

    private final ClassInfo classInfo;
    private final MethodInfo methodInfo;

    MethodReader(ClassInfo classInfo, MethodInfo methodInfo) {
        this.classInfo = classInfo;
        this.methodInfo = methodInfo;
    }

    @Override
    public Object read(Object instance) {
        Object value = MethodWriter.read(classInfo.getMethod(methodInfo), instance);
        if (methodInfo.hasConverter()) {
            value = methodInfo.getConverter().toGraphProperty(value);
        }
        return value;
    }

    @Override
    public String relationshipType() {
        return methodInfo.relationship();
    }

    @Override
    public String relationshipDirection() {
        ObjectAnnotations annotations = methodInfo.getAnnotations();
        if(annotations != null) {
            AnnotationInfo relationshipAnnotation = annotations.get(Relationship.CLASS);
            if(relationshipAnnotation != null) {
                return relationshipAnnotation.get(Relationship.DIRECTION, Relationship.UNDIRECTED);
            }
        }
        return Relationship.UNDIRECTED;
    }

    @Override
    public String typeParameterDescriptor() {
        return methodInfo.getTypeDescriptor();
    }

    @Override
    public String propertyName() {
        return methodInfo.property();
    }

}
