package com.thinkaurelius.titan.graphdb.query;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.core.attribute.Contain;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.thinkaurelius.titan.graphdb.query.condition.*;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Utility methods used in query optimization and processing.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class QueryUtil {

    public static int adjustLimitForTxModifications(StandardTitanTx tx, int uncoveredAndConditions, int limit) {
        assert limit > 0 && limit <= 1000000000; //To make sure limit computation does not overflow
        assert uncoveredAndConditions >= 0;

        if (uncoveredAndConditions > 0) {
            int maxMultiplier = Integer.MAX_VALUE / limit;
            limit = limit * Math.min(maxMultiplier, (int) Math.pow(2, uncoveredAndConditions)); //(limit*3)/2+1;
        }

        if (tx.hasModifications())
            limit += Math.min(Integer.MAX_VALUE - limit, 5);

        return limit;
    }

    public static InternalRelationType getType(StandardTitanTx tx, String typeName) {
        RelationType t = tx.getRelationType(typeName);
        if (t == null && !tx.getConfiguration().getAutoSchemaMaker().ignoreUndefinedQueryTypes()) {
            throw new IllegalArgumentException("Undefined type used in query: " + typeName);
        }
        return (InternalRelationType) t;
    }

    /**
     * Query-normal-form (QNF) for Titan is a variant of CNF (conjunctive normal form) with negation inlined where possible
     *
     * @param condition
     * @return
     */
    public static boolean isQueryNormalForm(Condition<?> condition) {
        if (isQNFLiteralOrNot(condition)) return true;
        else if (condition instanceof And) {
            for (Condition<?> child : ((And<?>) condition).getChildren()) {
                if (isQNFLiteralOrNot(child)) continue;
                else if (child instanceof Or) {
                    for (Condition<?> child2 : ((Or<?>) child).getChildren()) {
                        if (!isQNFLiteralOrNot(child2)) return false;
                    }
                } else return false;
            }
            return true;
        } else return false;
    }

    private static final boolean isQNFLiteralOrNot(Condition<?> condition) {
        if (condition instanceof Not) {
            Condition child = ((Not) condition).getChild();
            if (!isQNFLiteral(child)) return false;
            else if (child instanceof PredicateCondition) {
                return !((PredicateCondition) child).getPredicate().hasNegation();
            } else return true;
        } else return isQNFLiteral(condition);
    }

    private static final boolean isQNFLiteral(Condition<?> condition) {
        if (condition.getType() != Condition.Type.LITERAL) return false;
        if (condition instanceof PredicateCondition) {
            return ((PredicateCondition) condition).getPredicate().isQNF();
        } else return true;
    }

    private static final <E extends TitanElement> Condition<E> inlineNegation(Condition<E> condition) {
        if (ConditionUtil.containsType(condition, Condition.Type.NOT)) {
            return ConditionUtil.transformation(condition, new Function<Condition<E>, Condition<E>>() {
                @Nullable
                @Override
                public Condition<E> apply(@Nullable Condition<E> cond) {
                    if (cond instanceof Not) {
                        Condition<E> child = ((Not) cond).getChild();
                        Preconditions.checkArgument(child.getType() == Condition.Type.LITERAL); //verify QNF
                        if (child instanceof PredicateCondition) {
                            PredicateCondition<?, E> pc = (PredicateCondition) child;
                            if (pc.getPredicate().hasNegation()) {
                                return new PredicateCondition(pc.getKey(), pc.getPredicate().negate(), pc.getValue());
                            }
                        }
                    }
                    return null;
                }
            });
        } else return condition;
    }

    public static final <E extends TitanElement> Condition<E> simplifyQNF(Condition<E> condition) {
        Preconditions.checkArgument(isQueryNormalForm(condition));
        if (condition.numChildren() == 1) {
            Condition<E> child = ((And) condition).get(0);
            if (child.getType() == Condition.Type.LITERAL) return child;
        }
        return condition;
    }

    public static boolean isEmpty(Condition<?> condition) {
        return condition.getType() != Condition.Type.LITERAL && condition.numChildren() == 0;
    }

    /**
     * Prepares the constraints from the query builder into a QNF compliant condition.
     * If the condition is invalid or trivially false, it returns null.
     *
     * @param tx
     * @param constraints
     * @param <E>
     * @return
     * @see #isQueryNormalForm(com.thinkaurelius.titan.graphdb.query.condition.Condition)
     */
    public static <E extends TitanElement> And<E> constraints2QNF(StandardTitanTx tx, List<PredicateCondition<String, E>> constraints) {
        And<E> conditions = new And<E>(constraints.size() + 4);
        for (PredicateCondition<String, E> atom : constraints) {
            RelationType type = getType(tx, atom.getKey());

            if (type == null) {
                if (atom.getPredicate() == Cmp.EQUAL && atom.getValue() == null)
                    continue; //Ignore condition, its trivially satisfied

                return null;
            }

            Object value = atom.getValue();
            TitanPredicate predicate = atom.getPredicate();


            if (type.isPropertyKey()) {
                PropertyKey key = (PropertyKey) type;
                assert predicate.isValidCondition(value);
                Preconditions.checkArgument(key.getDataType()==Object.class || predicate.isValidValueType(key.getDataType()), "Data type of key is not compatible with condition");
            } else { //its a label
                Preconditions.checkArgument(((EdgeLabel) type).isUnidirected());
                Preconditions.checkArgument(predicate.isValidValueType(TitanVertex.class), "Data type of key is not compatible with condition");
            }

            if (predicate instanceof Contain) {
                //Rewrite contains conditions
                Collection values = (Collection) value;
                if (predicate == Contain.NOT_IN) {
                    for (Object invalue : values)
                        addConstraint(type, Cmp.NOT_EQUAL, invalue, conditions, tx);
                } else {
                    Preconditions.checkArgument(predicate == Contain.IN);
                    if (values.size() == 1) {
                        addConstraint(type, Cmp.EQUAL, values.iterator().next(), conditions, tx);
                    } else {
                        Or<E> nested = new Or<E>(values.size());
                        for (Object invalue : values)
                            addConstraint(type, Cmp.EQUAL, invalue, nested, tx);
                        conditions.add(nested);
                    }
                }
            } else {
                addConstraint(type, predicate, value, conditions, tx);
            }
        }
        return conditions;
    }

    private static <E extends TitanElement> void addConstraint(RelationType type, TitanPredicate predicate,
                                                               Object value, MultiCondition<E> conditions, StandardTitanTx tx) {
        if (type.isPropertyKey()) {
            if (value != null)
                value = tx.verifyAttribute((PropertyKey) type, value);
        } else { //t.isEdgeLabel()
            Preconditions.checkArgument(value instanceof TitanVertex);
        }
        PredicateCondition<RelationType, E> pc = new PredicateCondition<RelationType, E>(type, predicate, value);
        if (!conditions.contains(pc)) conditions.add(pc);
    }


    public static Map.Entry<RelationType,Collection> extractOrCondition(Or<TitanRelation> condition) {
        RelationType masterType = null;
        List<Object> values = new ArrayList<Object>();
        for (Condition c : condition.getChildren()) {
            if (!(c instanceof PredicateCondition)) return null;
            PredicateCondition<RelationType, TitanRelation> atom = (PredicateCondition)c;
            if (atom.getPredicate()!=Cmp.EQUAL) return null;
            Object value = atom.getValue();
            if (value==null) return null;
            RelationType type = atom.getKey();
            if (masterType==null) masterType=type;
            else if (!masterType.equals(type)) return null;
            values.add(value);
        }
        if (masterType==null) return null;
        assert !values.isEmpty();
        return new AbstractMap.SimpleImmutableEntry(masterType,values);
    }


    public static <R> List<R> processIntersectingRetrievals(List<IndexCall<R>> retrievals, final int limit) {
        Preconditions.checkArgument(!retrievals.isEmpty());
        Preconditions.checkArgument(limit >= 0, "Invalid limit: %s", limit);
        List<R> results;
        /*
         * Iterate over the clauses in the and collection
         * query.getCondition().getChildren(), taking the intersection
         * of current results with cumulative results on each iteration.
         */
        //TODO: smarter limit estimation
        int multiplier = Math.min(16, (int) Math.pow(2, retrievals.size() - 1));
        int sublimit = Integer.MAX_VALUE;
        if (Integer.MAX_VALUE / multiplier >= limit) sublimit = limit * multiplier;
        boolean exhaustedResults;
        do {
            exhaustedResults = true;
            results = null;
            for (IndexCall<R> call : retrievals) {
                Collection<R> subresult;
                try {
                    subresult = call.call(sublimit);
                } catch (Exception e) {
                    throw new TitanException("Could not process individual retrieval call ", e);
                }

                if (subresult.size() >= sublimit) exhaustedResults = false;
                if (results == null) {
                    results = Lists.newArrayList(subresult);
                } else {
                    Set<R> subresultset = ImmutableSet.copyOf(subresult);
                    Iterator riter = results.iterator();
                    while (riter.hasNext()) {
                        if (!subresultset.contains(riter.next())) riter.remove();
                    }
                }
            }
            sublimit = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(Math.pow(sublimit, 1.5),(sublimit+1)*2));
        } while (results.size() < limit && !exhaustedResults);
        return results;
    }


    public interface IndexCall<R> {

        public Collection<R> call(int limit);

    }

}
