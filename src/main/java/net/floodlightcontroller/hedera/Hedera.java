package net.floodlightcontroller.hedera;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.annotations.LogMessageDoc;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.forwarding.Forwarding;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.IRoutingDecision;
import org.openflow.protocol.OFPacketIn;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Hedera extends Forwarding implements IFloodlightModule {

    class matrixElement {

        HashMap<byte[], Integer> flowamount = new HashMap<byte[], Integer>();
        HashMap<byte[], Double> flowdemands = new HashMap<byte[], Double>();
        HashMap<byte[], Boolean> convergeFlag = new HashMap<byte[], Boolean>();
    }

    class TrafficEstimation implements Runnable {

        private boolean allflowconverged = false;

        private void estimateSource(Map.Entry<byte[], matrixElement> sourceRecord) {
            double convergedDemand = 0.0;
            int unconvergedFlowNumber = 0;
            for (Map.Entry<byte[], Boolean> destination :
                    sourceRecord.getValue().convergeFlag.entrySet()) {
                if (destination.getValue()) {
                    //it is converged
                    convergedDemand += sourceRecord.getValue().flowdemands.get(destination.getKey());
                } else {
                    unconvergedFlowNumber += 1;
                }
            }
            double estFlow = (1 - convergedDemand) / unconvergedFlowNumber;
            for (Map.Entry<byte[], Boolean> destination :
                    sourceRecord.getValue().convergeFlag.entrySet()) {
                if (!destination.getValue())
                    sourceRecord.getValue().flowdemands.put(destination.getKey(), estFlow);
            }
        }

        private void estimateDestination(Map.Entry<byte[], matrixElement> destinationRecord) {
            double totaldemand = 0.0;
            double senderlimitdemand = 0.0;
            int receiverlimitflownum = 0;
            HashMap<byte[], Boolean> flowreceiverLimited = new HashMap<byte[], Boolean>();
            for (Map.Entry<byte[], Double> source :
                    destinationRecord.getValue().flowdemands.entrySet()) {
                flowreceiverLimited.put(source.getKey(), true);
                totaldemand += source.getValue();
                receiverlimitflownum += 1;
            }
            if (totaldemand < 1) return;
            double estimatedDemand = totaldemand / receiverlimitflownum;
            boolean findreceiverlimitedflow = false;
            do {
                receiverlimitflownum = 0;
                for (Map.Entry<byte[], Double> source :
                        destinationRecord.getValue().flowdemands.entrySet()) {
                    if (!flowreceiverLimited.containsKey(source.getKey())) continue;
                    if (source.getValue() < estimatedDemand) {
                        senderlimitdemand += source.getValue();
                        flowreceiverLimited.remove(source.getKey());
                        findreceiverlimitedflow = true;
                    } else {
                        receiverlimitflownum += 1;
                    }
                }
                estimatedDemand = (totaldemand - senderlimitdemand) / receiverlimitflownum;
            } while (findreceiverlimitedflow);
            for (Map.Entry<byte[], Double> source :
                    destinationRecord.getValue().flowdemands.entrySet()) {
                sourceFlowMap.get(source.getKey()).convergeFlag.put(
                        destinationRecord.getKey(), true);
                if (estimatedDemand !=
                        sourceFlowMap.get(source.getKey()).flowdemands.get(destinationRecord.getKey())) {
                    allflowconverged = false;
                }
                sourceFlowMap.get(source.getKey()).flowdemands.put(
                        destinationRecord.getKey(), estimatedDemand);
            }
        }

        @Override
        public void run() {
            while (!allflowconverged) {
                try {
                    Thread.sleep(1000 * 10);
                } catch (InterruptedException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
                allflowconverged = true;
                for (Map.Entry<byte[], matrixElement> sourceRecord : sourceFlowMap.entrySet()) {
                    estimateSource(sourceRecord);
                    //foreach source
                }
                for (Map.Entry<byte[], matrixElement> destinationRecord : destinationFlowMap.entrySet()) {
                    estimateDestination(destinationRecord);
                    //foreach source
                }
            }
        }
    }


    private ConcurrentHashMap<byte[], matrixElement> sourceFlowMap = new
            ConcurrentHashMap<byte[], matrixElement>();
    private ConcurrentHashMap<byte[], matrixElement> destinationFlowMap = new
            ConcurrentHashMap<byte[], matrixElement>();

    private void fillTrafficMatrix(Ethernet eth) {
        //1. check if sourceFlowMap exists
        if (!sourceFlowMap.contains(eth)) {
            sourceFlowMap.put(eth.getSourceMACAddress(), new matrixElement());
        }
        //2. check if destinationFlowMap exists
        if (!destinationFlowMap.contains(eth)) {
            destinationFlowMap.put(eth.getSourceMACAddress(), new matrixElement());
        }
    }

    @Override
    @LogMessageDoc(level="ERROR",
            message="Unexpected decision made for this packet-in={}",
            explanation="An unsupported PacketIn decision has been " +
                    "passed to the flow programming component",
            recommendation=LogMessageDoc.REPORT_CONTROLLER_BUG)
    public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
                                          FloodlightContext cntx) {
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

        // If a decision has been made we obey it
        // otherwise we just forward
        if (decision != null) {
            if (log.isTraceEnabled()) {
                log.trace("Forwaring decision={} was made for PacketIn={}",
                        decision.getRoutingAction().toString(),
                        pi);
            }
            log.debug("routing action has been made");
            switch(decision.getRoutingAction()) {
                case NONE:
                    // don't do anything
                    return Command.CONTINUE;
                case FORWARD_OR_FLOOD:
                case FORWARD:
                    fillTrafficMatrix(eth);
                    doForwardFlow(sw, pi, cntx, false);
                    return Command.CONTINUE;
                case MULTICAST:
                    // treat as broadcast
                    fillTrafficMatrix(eth);
                    doFlood(sw, pi, cntx);
                    return Command.CONTINUE;
                case DROP:
                    doDropFlow(sw, pi, decision, cntx);
                    return Command.CONTINUE;
                default:
                    log.error("Unexpected decision made for this packet-in={}",
                            pi, decision.getRoutingAction());
                    return Command.CONTINUE;
            }
        } else {
            fillTrafficMatrix(eth);
            if (log.isTraceEnabled()) {
                log.trace("No decision was made for PacketIn={}, forwarding",
                        pi);
            }
            log.debug("receive a PACKET_IN message, pi = " + pi);
            if (eth.isBroadcast() || eth.isMulticast()) {
                // For now we treat multicast as broadcast
                log.debug("no target host information, then flood");
                doFlood(sw, pi, cntx);
            } else {
                doForwardFlow(sw, pi, cntx, false);
            }
        }

        return Command.CONTINUE;
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        super.startUp();
        //start estimation thread
        Thread t = new Thread(new TrafficEstimation());
        t.start();
    }
}
