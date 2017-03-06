import java.util.*;

public class DecisionTree implements Classifier {

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        root.printMyself(sb,0);
        return sb.toString();
    }

    private static final double oo = (1L<<62);
    private Node root = null;

    private static int nodeCount = 0;

    private final DataHolder dataHolder = DataHolder.getInstance(null);
    Long []trainingData;

    @Override
    public void trainOnData( final Long[] trainingData, final int len ) {
        this.trainingData = trainingData;
        nodeCount = 0;
        root = new Node(MyUtils.MASK(dataHolder.getN())&~MyUtils.BIT(dataHolder.getTargVariable()),0,len-1,null);
    }

    @Override
    public int getPrediction(Long t) {
        return getPrediction(root,t);
    }

    private int getPrediction( Node x, Long t ) {
       assert x != null;
       if ( x.isTerminalState ) {
           if ( x.frac == null ) // that means the node is homogeneous
            return x.value;
           return x.frac.doubleValue()>=0.5?0:1;
       }
       assert x.splittingVarIdx != -1;
       long kk = dataHolder.readAttribute(t,x.splittingVarIdx);
       if ( !x.child.containsKey(kk) )
           return x.frac.doubleValue()>=0.5?0:1; // if there is no branch for a particular value, return majority class
       return getPrediction(x.child.get(dataHolder.readAttribute(t,x.splittingVarIdx)),t);
    }

    private class Node {
        boolean isTerminalState = false;
        final long signature;
        final int left, right;
        final Node p;
        int splittingVarIdx = -1, value;
        Map<Long,Node> child = null;
        Double frac = null;

        void printMyself( StringBuilder sb, int offset ) {
            if ( isTerminalState ) {
                for ( int k = offset; k-->0; sb.append(" ") );
                sb.append(String.format("%s","then "+dataHolder.getTargVarName()+" is "+dataHolder.getTargClass(value+1)));
                if ( frac != null && Math.abs(frac-0.00) >= MyUtils.tol && Math.abs(frac-1.00) >= MyUtils.tol ) // if frac != 0 && frac != 1.00, i.e. the node is terminal but mixed
                    sb.append(String.format("(Pr = %.2f%%)\n",frac*100));       // append the probability information as well
                else sb.append("\n");
                return ;
            }
            int i = 0;
            assert splittingVarIdx >= 0;
            String colname = dataHolder.getNameOfAttribute(splittingVarIdx);
            for ( Map.Entry<Long,Node> entry: child.entrySet() ) {
                String nm = dataHolder.getFieldValueName(entry.getKey(),splittingVarIdx);
                for ( int k = offset; k-->0; sb.append(" ") );
                sb.append(String.format("%s\n",(++i>1?"else ":"")+"if "+colname+" == "+nm+", "));
                entry.getValue().printMyself(sb,offset+4);
            }
        }

        Node( Long signature, int left, int right, Node p ) {
            this.signature = signature;
            this.left = left;
            this.right = right;
            this.p = p;
            assert left <= right;
            ++nodeCount;
            if ( isHomogeneous(this) ) {
                isTerminalState = true ;
                value = dataHolder.getOutcome(trainingData[left]);
            }
            else {
                assert frac != null ;
                if ( 0 == signature ) { // if data is not homogeneous and
                    this.isTerminalState = true ;     // we've run out of attributes
                    if ( Math.abs(frac-0.5) >= MyUtils.tol ) { // if there is a clear winner...
                        value = frac>0.5?0:1; //frac gives the fraction of 0-labels
                        return ;
                    }
                    if ( p == null || p.frac == null ) {
                        frac = 0.5;
                        value = 0;
                    }
                    else value = (frac=p.frac)>0.5?0:1;
                    return ;
                }
                /*
                 * doSplit() functionality
                 */
                final int idx = determineSplittingVarIdx();
                assert (signature & MyUtils.BIT(idx)) != 0: signature+" "+idx;
                Arrays.sort(trainingData, left, right + 1, new Comparator<Long>() {
                    @Override
                    public int compare(Long o1, Long o2) {
                        long x1 = dataHolder.readAttribute(o2,idx), x2 = dataHolder.readAttribute(o1,idx);
                        if ( x1 == x2 ) return 0;
                        return x1<x2?-1:1;
                    }
                });
                child = new HashMap<>();
                for ( int j,i = left; i <= right; i = j ) {
                    long kk = dataHolder.readAttribute(trainingData[i],idx);
                    for ( j = i; j <= right && dataHolder.readAttribute(trainingData[j],idx) == kk; ++j );
                    child.put(kk,new Node(signature&~MyUtils.BIT(idx),i,j-1,this));
                }
                splittingVarIdx = idx;
                assert splittingVarIdx >= 0;
            }
        }

        private boolean isHomogeneous( Node x ) {
            assert left <= right;
            Arrays.sort(trainingData, left, right + 1, new Comparator<Long>() {
                @Override
                public int compare(Long o1, Long o2) {
                    long out1 = dataHolder.getActualOutcome(o1), out2 = dataHolder.getActualOutcome(o2);
                    if ( out1 == out2 ) return 0;
                    return out1<out2?-1:1;
                }
            });
            boolean zPresent = false, oPresent = false ;
            for ( int i = left; i <= right && !(zPresent && oPresent); ++i )
                if ( dataHolder.getOutcome(trainingData[i]) == 0 ) zPresent = true ;
                else oPresent = true ;
            if ( zPresent^oPresent ) return true ;
            int z = 0, o = 0;
            for ( int i = left; i <= right; ++i )
                if ( dataHolder.getOutcome(trainingData[i]) == 0 ) ++z;
                else ++o;
            x.frac = Double.valueOf((z+0.00)/(z+o));
            return false ;
        }

        private int determineSplittingVarIdx() {
            int bestVarIdx = -1;
            double minEA = +oo;
            for ( long u = signature; u > 0; u &= ~MyUtils.LSB(u) ) {
               final int idx = MyUtils.who(MyUtils.LSB(u));
               assert ( idx != dataHolder.getTargVariable() ) ;
               assert (signature & MyUtils.BIT(idx)) != 0;
               Arrays.sort(trainingData, left, right + 1, new Comparator<Long>() {
                   @Override
                   public int compare( Long o1, Long o2 ) {
                       long x1 = dataHolder.readAttribute(o1,idx), x2 = dataHolder.readAttribute(o2,idx);
                       if ( x1 == x2 ) {
                           long out1 = dataHolder.getActualOutcome(o1), out2 = dataHolder.getActualOutcome(o2);
                           if ( out1 == out2 ) return 0;
                           return out1<out2?-1:1;
                       }
                       return x1<x2?-1:1;
                   }
               });
               /*
                * getInformationGain() functionality
                */
               double EA = 0;
               int differentValues = 0;
               for ( int j,i = left; i <= right; i = j, ++differentValues ) {
                   long kk = dataHolder.readAttribute(trainingData[i],idx);
                   int zeros = 0, ones;
                   for ( j = i; j <= right && dataHolder.readAttribute(trainingData[j],idx) == kk; ++j )
                       if ( dataHolder.getOutcome(trainingData[j]) == 0 )
                           ++zeros;
                   ones = (j-i)-zeros;
                   int segmentSize = ones+zeros;
                   if ( (EA += (segmentSize+0.00)/(right-left+1)*MyUtils.I(zeros,ones)) >= minEA ) {
                       EA = +oo;
                       break ;
                   }
               }
               if ( EA < minEA ) {
                   minEA = EA;
                   bestVarIdx = idx;
               }
            }
            return bestVarIdx;
        }
    }

}

