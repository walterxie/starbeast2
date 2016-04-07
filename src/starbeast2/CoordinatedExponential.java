package starbeast2;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import beast.core.Description;
import beast.core.Input;
import beast.evolution.tree.Node;
import beast.evolution.tree.TreeInterface;
import beast.util.Randomizer;

/**
* @author Huw Ogilvie
 */

@Description("Implements a version of the co-ordinated species and gene tree operator described in Jones (2015)."
        + "Specifically, this operator moves the species tree root node and a set of gene tree nodes related to the"
        + "species tree node by a uniform amount chosen from an exponential distribution, offset to preserve the"
        + "topology of all trees. See http://dx.doi.org/10.1101/010199 for full details.")
public class CoordinatedExponential extends CoordinatedOperator {
    public final Input<Double> betaInput = new Input<>("beta", "Beta parameter of the exponential proposal distribution", 1.0);
    public final Input<Boolean> optimiseInput = new Input<>("optimise", "Adjust 'k' during the MCMC run to improve mixing.", true);

    protected boolean optimise;
    private double beta;
    private double lambda;
    private enum descendsThrough {
       LEFT_ONLY, RIGHT_ONLY, BOTH, NEITHER
    }

    @Override
    public void initAndValidate() {
        beta = betaInput.get();
        lambda = 1.0 / beta;
        optimise = optimiseInput.get();
    }

    /**
     * override this for proposals,
     *
     * @return log of Hastings Ratio, or Double.NEGATIVE_INFINITY if proposal should not be accepted *
     */
    @Override
    public double proposal() {
        final TreeInterface speciesTree = speciesTreeInput.get().getTree();

        // always operate on the root node
        Node speciesTreeRoot = speciesTree.getRoot();

        final double currentRootHeight = speciesTreeRoot.getHeight();
        final double leftChildHeight = speciesTreeRoot.getLeft().getHeight();
        final double rightChildHeight = speciesTreeRoot.getRight().getHeight();

        final MinimumDouble tipwardFreedom = new MinimumDouble();
        final SetMultimap<Integer, Node> connectingNodes = getConnectingNodes(speciesTreeRoot, tipwardFreedom);

        tipwardFreedom.set(currentRootHeight - leftChildHeight);
        tipwardFreedom.set(currentRootHeight - rightChildHeight);

        // the youngest age the species tree root node can be (preserving topologies)
        final double uniformShift = Randomizer.nextExponential(lambda) - tipwardFreedom.get();

        speciesTreeRoot.setHeight(currentRootHeight + uniformShift);
        for (Node geneTreeNode: connectingNodes.values()) {
            geneTreeNode.setHeight(geneTreeNode.getHeight() + uniformShift);
        }

        // the log ratio of the density of the proposed over the current species tree root heights
        final double fLogHastingsRatio = lambda * uniformShift;

        return fLogHastingsRatio;
    }

    // identify gene tree nodes which descend through both (and also descend exclusively through)
    // the left and right children of the species tree node of interest
    private SetMultimap<Integer, Node> getConnectingNodes(Node speciesTreeNode, MinimumDouble tipwardFreedom) {
        final Node leftChildNode = speciesTreeNode.getLeft();
        final Node rightChildNode = speciesTreeNode.getRight();
        final int leftChildNodeNumber = leftChildNode.getNr();
        final int rightChildNodeNumber = rightChildNode.getNr();
        final Set<String> leftChildDescendants = findDescendants(leftChildNode, leftChildNodeNumber);
        final Set<String> rightChildDescendants = findDescendants(rightChildNode, rightChildNodeNumber);

        final SetMultimap<Integer, Node> allConnectingNodes = HashMultimap.create();
        final List<TreeInterface> geneTrees = geneTreeInput.get();
        for (int j = 0; j < nGeneTrees; j++) {
            final Node geneTreeRootNode = geneTrees.get(j).getRoot();
            final Set<Node> jConnectingNodes = new HashSet<Node>();
            findConnectingNodes(geneTreeRootNode, jConnectingNodes, leftChildDescendants, rightChildDescendants, tipwardFreedom);
            allConnectingNodes.putAll(j, jConnectingNodes);
        }

        return allConnectingNodes;
    }

    private descendsThrough findConnectingNodes(Node geneTreeNode, Set<Node> connectingNodes, Set<String> leftChildDescendants, Set<String> rightChildDescendants, MinimumDouble tipwardFreedom) {
        if (geneTreeNode.isLeaf()) {
            final String descendantName = geneTreeNode.getID();
            if (leftChildDescendants.contains(descendantName)) {
                return descendsThrough.LEFT_ONLY;
            } else if (rightChildDescendants.contains(descendantName)) {
                return descendsThrough.RIGHT_ONLY;
            } else {
                return descendsThrough.NEITHER;
            }
        }

        final Node leftChild = geneTreeNode.getLeft();
        final Node rightChild = geneTreeNode.getRight();
        final descendsThrough leftDescent = findConnectingNodes(leftChild, connectingNodes, leftChildDescendants, rightChildDescendants, tipwardFreedom);
        final descendsThrough rightDescent = findConnectingNodes(rightChild, connectingNodes, leftChildDescendants, rightChildDescendants, tipwardFreedom);

        if (leftDescent == rightDescent) {
            if (leftDescent == descendsThrough.BOTH) {
                connectingNodes.add(geneTreeNode);
            }

            return leftDescent;
        }

        // this code only executes when the left and right gene tree child nodes descend through different species tree node of interest children
        final double geneTreeNodeHeight = geneTreeNode.getHeight();
        if (leftDescent == descendsThrough.BOTH) { // the gene tree node left child is a member of a connected component
            if (rightDescent == descendsThrough.NEITHER) { // the gene tree node left child is the root node of a connected component
                return descendsThrough.NEITHER;
            } else { // the gene tree node right child descends exclusively through the left XOR right child of the species tree node of interest
                // so the current gene tree node is part of a connected component but the right child is not
                final double connectedComponentDescendantBranchLength = geneTreeNodeHeight - rightChild.getHeight();
                tipwardFreedom.set(connectedComponentDescendantBranchLength);
                connectingNodes.add(geneTreeNode);
                return descendsThrough.BOTH;
            }
        } else if (rightDescent == descendsThrough.BOTH) { // the gene tree node right child is a member of a connected component
            if (leftDescent == descendsThrough.NEITHER) { // the gene tree node right child is the root node of a connected component
                return descendsThrough.NEITHER;
            } else { // the gene tree node left child descends exclusively through the left XOR right child of the species tree node of interest
             // so the current gene tree node is part of a connected component but the left child is not
                final double connectedComponentTipFreedom = geneTreeNodeHeight - leftChild.getHeight();
                tipwardFreedom.set(connectedComponentTipFreedom);
                connectingNodes.add(geneTreeNode);
                return descendsThrough.BOTH;
            }
        } else if (leftDescent == descendsThrough.NEITHER || rightDescent == descendsThrough.NEITHER) {
            return descendsThrough.NEITHER; // the current gene tree node does not descend exclusively through the species tree node of interest
        } else { // this is a tip node of a connected component
            final double leftChildBranchLength = geneTreeNodeHeight - leftChild.getHeight();
            final double rightChildBranchLength = geneTreeNodeHeight - rightChild.getHeight();
            tipwardFreedom.set(leftChildBranchLength);
            tipwardFreedom.set(rightChildBranchLength);
            connectingNodes.add(geneTreeNode);
            return descendsThrough.BOTH;
        }
    }

    /*@Override
    public double getCoercableParameterValue() {
        return beta;
    }

    @Override
    public void setCoercableParameterValue(final double value) {
        beta = value;
        lambda = 1.0 / beta;
    }

    @Override
    public void optimize(final double logAlpha) {
        if (optimise) {
            final double currentBeta = beta;
            final double delta = calcDelta(logAlpha);
            final double logBeta = delta + Math.log(currentBeta);
            setCoercableParameterValue(Math.exp(logBeta));
            // System.out.println(String.format("%f = exp(%f) = exp(cd(%g) + ln(%f)) = exp(%f + ln(%f))", continuousK, logK, logAlpha, currentK, delta, currentK));
        }
    }

    @Override
    public final String getPerformanceSuggestion() {
        final double prob = m_nNrAccepted / (m_nNrAccepted + m_nNrRejected + 0.0);
        final double targetProb = getTargetAcceptanceProbability();

        double ratio = prob / targetProb;
        if (ratio > 2.0) ratio = 2.0;
        if (ratio < 0.5) ratio = 0.5;

        final double newBeta = beta * ratio;

        if (prob < 0.10 || prob > 0.40) {
            return String.format("Try setting beta to about %f", newBeta);
        } else {
            return "";
        }
    }*/
}