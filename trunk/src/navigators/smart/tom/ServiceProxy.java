/**
 * Copyright (c) 2007-2009 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags
 *
 * This file is part of SMaRt.
 *
 * SMaRt is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SMaRt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with SMaRt.  If not, see <http://www.gnu.org/licenses/>.
 */
package navigators.smart.tom;

//import br.ufsc.das.communication.SimpleCommunicationSystem;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.Semaphore;

import navigators.smart.reconfiguration.ReconfigurationManager;
import navigators.smart.reconfiguration.ReconfigureReply;
import navigators.smart.reconfiguration.View;
import navigators.smart.tom.core.messages.TOMMessage;
import navigators.smart.tom.util.Extractor;
import navigators.smart.tom.util.Logger;
import navigators.smart.tom.util.TOMUtil;

/**
 * This class implements a TOMSender and represents a proxy to be used on the
 * client side of the replicated system.
 * It sends a request to the replicas, receives the reply, and delivers it to
 * the application.
 */
public class ServiceProxy extends TOMSender {

    //******* EDUARDO BEGIN **************//
    // Semaphores used to render this object thread-safe
    // TODO: o mutex nao poderia ser antes um reentrantlock, em vez de um semaforo?
    //Eduardo: Acho que pode até ser um reentrantlock, mas apenas pode ser liberado (no caso do semaforo é a mesma coisa)
    //após a operação completar (não pode ser liberado após apenas o envio pq reqId será perdido)
    private Semaphore mutex = new Semaphore(1);
    private Semaphore mutexToSend = new Semaphore(1);
    //******* EDUARDO END **************//
    private Semaphore sm = new Semaphore(0);
    private int reqId = -1; // request id
    private int replyQuorum = 0; // size of the reply quorum
    private TOMMessage replies[] = null; // Replies from replicas are stored here
    private int receivedReplies = 0; // Number of received replies
    private TOMMessage response = null; // Reply delivered to the application
    private LinkedList<TOMMessage> aheadOfTimeReplies = new LinkedList<TOMMessage>();
    private Comparator comparator;
    private Extractor extractor;

    /**
     * Constructor
     *
     * @see bellow
     */
    public ServiceProxy(int processId) {
        this(processId, null, null, null);
    }

    /**
     * Constructor
     *
     * @see bellow
     */
    public ServiceProxy(int processId, String configHome) {
        this(processId, configHome, null, null);
    }

    /**
     * Constructor
     *
     * @param id Process id for this client (should be different from replicas)
     * @param configHome Configuration directory for BFT-SMART
     * @param replyComparator used for comparing replies from different servers
     *                        to extract one returned by f+1
     * @param replyExtractor used for extracting the response from the matching
     *                       quorum of replies
     */
    public ServiceProxy(int processId, String configHome,
                              Comparator replyComparator, Extractor replyExtractor) {
        if (configHome == null) {
            init(processId);
        } else {
            init(processId, configHome);
        }

        replies = new TOMMessage[getViewManager().getCurrentViewN()];

        comparator = (replyComparator != null)? replyComparator : new Comparator<byte[]>() {
            @Override
            public int compare(byte[] o1, byte[] o2) {
                return Arrays.equals(o1, o2) ? 0 : -1;
            }
        };
        
        extractor = (replyExtractor != null)? replyExtractor : new Extractor() {
            @Override
            public TOMMessage extractResponse(TOMMessage[] replies, int sameContent, int lastReceived) {
                return replies[lastReceived];
            }
        };
    }

    /**
     * This method sends a request to the replicas, and returns the related reply. This method is
     * thread-safe.
     *
     * @param request Request to be sent
     * @return The reply from the replicas related to request
     */
    public byte[] invoke(byte[] request) {
        return invoke(request, ReconfigurationManager.TOM_NORMAL_REQUEST, false);
    }

    public byte[] invoke(byte[] request, boolean readOnly) {
        return invoke(request, ReconfigurationManager.TOM_NORMAL_REQUEST, readOnly);
    }

    /**
     * This method sends a request to the replicas, and returns the related reply.
     * This method is thread-safe.
     *
     * @param request Request to be sent
     * @param reqType TOM_NORMAL_REQUESTS for service requests, and other for
     *        reconfig requests.
     * @param readOnly it is a read only request (will not be ordered)
     * @return The reply from the replicas related to request
     */
    public byte[] invoke(byte[] request, int reqType, boolean readOnly) {

        // Ahead lies a critical section.
        // This ensures the thread-safety by means of a semaphore
        try {
            this.mutexToSend.acquire();
        } catch (Exception e) {}

        // Clean all statefull data to prepare for receiving next replies
        Arrays.fill(replies, null);
        receivedReplies = 0;
        response = null;
        //if n=3f+1, read-only requests wait for 2f+1 matching replies while normal
        //requests wait for only f+1
        replyQuorum = readOnly?
                ((int) Math.ceil((getViewManager().getCurrentViewN() + getViewManager().getCurrentViewF()) / 2) + 1):
                (getViewManager().getCurrentViewF() + 1);

        Logger.println("Sending request (readOnly = "+readOnly+")");
        Logger.println("Expected number of matching replies: "+replyQuorum);

        // Send the request to the replicas, and get its ID
        doTOMulticast(request, reqType, readOnly);
        reqId = getLastSequenceNumber();

        // This instruction blocks the thread, until a response is obtained.
        // The thread will be unblocked when the method replyReceived is invoked
        // by the client side communication system
        try {
            this.sm.acquire();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

        Logger.println("Response extracted = "+response);

        if (response == null) {
            //the response can be null if n-f replies are received but there isn't
            //a replyQuorum of matching replies
            Logger.println("Received n-f replies and no response could be extracted.");

            mutexToSend.release();

            if(readOnly) {
                //invoke the operation again, whitout the read-only flag
                Logger.println("###############################################");
                Logger.println("###################RETRY#######################");
                Logger.println("###############################################");
                return invoke(request, reqType, false);
            } else {
                throw new RuntimeException("Received n-f replies without f+1 of them matching.");
            }
        } else {
            //normal operation
            //******* EDUARDO BEGIN **************//
            byte[] ret = null;
            if (reqType == ReconfigurationManager.TOM_NORMAL_REQUEST) {
                //Reply to a normal request!
                if (response.getViewID() == getViewManager().getCurrentViewId()) {
                    ret = response.getContent(); // return the response
                    mutexToSend.release();
                    return ret;
                } else {//if(response.getViewID() > getViewManager().getCurrentViewId())
                    //updated view received
                    reconfigureTo((View) TOMUtil.getObject(response.getContent()));
                    mutexToSend.release();
                    return invoke(request, reqType, readOnly);
                }
            } else {
                //Reply to a reconfigure request!
                Logger.println("Reconfiguration request' reply received!");
                //É "impossivel" ser menor!
                if (response.getViewID() > getViewManager().getCurrentViewId()) {
                    Object r = TOMUtil.getObject(response.getContent());
                    if (r instanceof View) { //Não executou esta requisição pq a visao estava desatualizada
                        reconfigureTo((View) r);
                        mutexToSend.release();
                        return invoke(request, reqType, readOnly);
                    } else { //Executou a requisição de reconfiguração
                        reconfigureTo(((ReconfigureReply) r).getView());
                        ret = response.getContent(); // return the response
                        mutexToSend.release();
                        return ret;
                    }
                } else {
                    //Caso a reconfiguração nao foi executada porque algum parametro
                    // da requisição estava incorreto: o processo queria fazer algo que nao é permitido
                    ret = response.getContent(); // return the response
                    mutexToSend.release();
                    return ret;
                }
            }
        }
        //******* EDUARDO END **************//
    }

    //******* EDUARDO BEGIN **************//
    private void reconfigureTo(View v) {
        Logger.println("Installing a most up-to-date view");
        getViewManager().reconfigureTo(v);
        replies = new TOMMessage[getViewManager().getCurrentViewN()];
        getCommunicationSystem().updateConnections();
    }
    //******* EDUARDO END **************//

    /**
     * This is the method invoked by the client side comunication system.
     *
     * @param reply The reply delivered by the client side comunication system
     */
    @Override
    public void replyReceived(TOMMessage reply) {
        Logger.println("reply received: sender="+reply.getSender()+" reqId="+reply.getSequence());
        // Ahead lies a critical section.
        // This ensures the thread-safety by means of a semaphore
        try {
            this.mutex.acquire();
        } catch (Exception e) {
            e.printStackTrace();
        }

        //******* EDUARDO BEGIN **************//
        int pos = getViewManager().getCurrentViewPos(reply.getSender());
        if (pos < 0) { //ignore messages that don't come from replicas
            this.mutex.release();
            return;
        }
        //******* EDUARDO END **************//

        if (reply.getSequence() > reqId) { // Is this a reply for the last request sent?
            Logger.println("Storing reply from "+reply.getSender()+" with reqId:"+reply.getSequence());
            aheadOfTimeReplies.add(reply);
        } else if(reply.getSequence() == reqId) {
            Logger.println("Receiving reply from "+reply.getSender()+" with reqId:"+reply.getSequence()+". Putting on pos="+pos);

            if(receivedReplies == 0) {
                //If this is the first reply received for reqId, lets look at ahead
                //of time messages to process possible messages for this reqId that
                //were already received
                for(ListIterator<TOMMessage> li = aheadOfTimeReplies.listIterator(); li.hasNext(); ) {
                    TOMMessage rr = li.next();
                    if(rr.getSequence() == reqId) {
                        Logger.println("Adding old message.");
                        int rpos = getViewManager().getCurrentViewPos(rr.getSender());
                        receivedReplies++;
                        replies[rpos] = rr;
                        li.remove();
                    }
                }
            }

            receivedReplies++;
            replies[pos] = reply;

            // Compare the reply just received, to the others
            int sameContent = 1;
            for (int i = 0; i < replies.length; i++) {
                    Logger.println(i+" ("+reqId+"): "+sameContent+"/"+receivedReplies+"/"+replyQuorum);
                if (i != pos && replies[i] != null && (comparator.compare(replies[i].getContent(), reply.getContent()) == 0)) {
                    sameContent++;
                    
                    if (sameContent >= replyQuorum) {
                        response = extractor.extractResponse(replies, sameContent, pos);
                        reqId = -1;
                        this.sm.release(); // resumes the thread that is executing the "invoke" method
                        break;
                    }
                }
            }

            if (response == null && receivedReplies >= /*n-f*/
                    getViewManager().getCurrentViewN() - getViewManager().getCurrentViewF()) {
                //it's not safe to wait for more replies (n-f replies received),
                //but there is no response available...
                reqId = -1;
                Logger.println("releasing sm without response");
                this.sm.release(); // resumes the thread that is executing the "invoke" method
            }
        } else {
            Logger.println("Discarding reply from "+reply.getSender()+" with reqId:"+reply.getSequence()+". Putting on pos="+pos);
        }

        // Critical section ends here. The semaphore can be released
        this.mutex.release();
    }
}