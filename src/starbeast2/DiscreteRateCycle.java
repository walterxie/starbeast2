package starbeast2;

import beast.core.Input;
import beast.core.parameter.IntegerParameter;
import beast.core.Input.Validate;

/**
 *
 * @author Huw A. Ogilvie
 * 
 */

public class DiscreteRateCycle extends AdaptiveOperator {
    final public Input<IntegerParameter> treeRatesInput = new Input<>("treeRates", "The branch rates.", Validate.REQUIRED);

    private int nNodes;

    @Override
    public void initAndValidate() {
        final IntegerParameter treeRates = treeRatesInput.get();
        nNodes = treeRates.getDimension();
        setLimits(3, nNodes);
        super.initAndValidate();
    }

    // symmetric proposal distribution
    @Override
    public double proposal() {
        final IntegerParameter treeRates = treeRatesInput.get();
        final Integer[] treeRatesArray = treeRates.getValues();
        final int[] cycle = chooseK(nNodes);

        final int lastNodeNumber = cycle[discreteK - 1];
        int previousRate = treeRatesArray[lastNodeNumber];
        for (int i = 0; i < discreteK; i++) {
            final int nodeNumber = cycle[i];
            treeRates.setValue(nodeNumber, previousRate);
            previousRate = treeRatesArray[nodeNumber];
        }

        return 0.0;
    }
}
