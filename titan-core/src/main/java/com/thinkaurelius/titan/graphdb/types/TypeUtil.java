package com.thinkaurelius.titan.graphdb.types;

import com.google.common.collect.Sets;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.Cardinality;
import com.thinkaurelius.titan.core.schema.ConsistencyModifier;
import com.thinkaurelius.titan.graphdb.database.management.ModifierType;
import com.thinkaurelius.titan.graphdb.internal.ElementCategory;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.tinkerpop.blueprints.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public class TypeUtil {

    public static boolean hasSimpleInternalVertexKeyIndex(TitanRelation rel) {
        if (!(rel instanceof TitanProperty)) return false;
        else return hasSimpleInternalVertexKeyIndex((TitanProperty)rel);
    }

    public static boolean hasSimpleInternalVertexKeyIndex(TitanProperty prop) {
        return hasSimpleInternalVertexKeyIndex(prop.getPropertyKey());
    }

    public static boolean hasSimpleInternalVertexKeyIndex(PropertyKey key) {
        InternalRelationType type = (InternalRelationType)key;
        for (IndexType index : type.getKeyIndexes()) {
            if (index.getElement()== ElementCategory.VERTEX && index.isCompositeIndex()) {
                if (index.indexesKey(key)) return true;
//                InternalIndexType iIndex = (InternalIndexType)index;
//                if (iIndex.getFieldKeys().length==1) {
//                    assert iIndex.getFieldKeys()[0].getFieldKey().equals(key);
//                    return true;
//                }
            }
        }
        return false;
    }

    public static InternalRelationType getBaseType(InternalRelationType type) {
        InternalRelationType baseType = type.getBaseType();
        if (baseType == null) return type;
        else return baseType;
    }

    public static Set<PropertyKey> getIndexedKeys(IndexType index) {
        Set<PropertyKey> s = Sets.newHashSet();
        for (IndexField f : index.getFieldKeys()) {
            s.add(f.getFieldKey());
        }
        return s;
    }

    public static List<CompositeIndexType> getUniqueIndexes(PropertyKey key) {
        List<CompositeIndexType> indexes = new ArrayList<CompositeIndexType>();
        for (IndexType index : ((InternalRelationType)key).getKeyIndexes()) {
            if (index.isCompositeIndex()) {
                CompositeIndexType iIndex = (CompositeIndexType)index;
                assert index.indexesKey(key);
                if (iIndex.getCardinality()== Cardinality.SINGLE) {
                    assert iIndex.getElement()==ElementCategory.VERTEX;
                    indexes.add(iIndex);
                }
            }
        }
        return indexes;
    }

    private static <T> T getTypeModifier(final SchemaSource schema,
                                         final ModifierType modifierType,
                                         final T defaultValue) {
        for (SchemaSource.Entry entry : schema.getRelated(TypeDefinitionCategory.TYPE_MODIFIER, Direction.OUT)) {
            T value = entry.getSchemaType().getDefinition().getValue(modifierType.getCategory());
            if (null != value) {
                return value;
            }
        }
        return defaultValue;
    }

    public static ConsistencyModifier getConsistencyModifier(SchemaSource schema) {
        return getTypeModifier(schema, ModifierType.CONSISTENCY, ConsistencyModifier.DEFAULT);
    }

    public static int getTTL(final SchemaSource schema) {
        return getTypeModifier(schema, ModifierType.TTL, 0).intValue();
    }
}
