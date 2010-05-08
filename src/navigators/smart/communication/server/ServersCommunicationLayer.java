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

package navigators.smart.communication.server;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import navigators.smart.tom.core.messages.SystemMessage;
import navigators.smart.tom.util.TOMConfiguration;


/**
 *
 * @author alysson
 */
public class ServersCommunicationLayer extends Thread {

    private TOMConfiguration conf;
    private LinkedBlockingQueue<SystemMessage> inQueue;
    private ServerConnection[] connections;
    private ServerSocket serverSocket;
    private int me;
    private boolean doWork = true;

    public ServersCommunicationLayer(TOMConfiguration conf, LinkedBlockingQueue<SystemMessage> inQueue) throws Exception {
        this.conf = conf;
        this.inQueue = inQueue;
        this.me = conf.getProcessId();

        connections = new ServerConnection[conf.getN()];
        for (int i = 0; i < connections.length; i++) {
            if (i == me) {
                connections[i] = null;
            } else {
                connections[i] = new ServerConnection(conf, null, i, inQueue);
            }
        }

        serverSocket = new ServerSocket(conf.getPort(conf.getProcessId()));        
        serverSocket.setSoTimeout(10000);
        serverSocket.setReuseAddress(true);
        
        start();
    }

    public final void send(int[] targets, SystemMessage sm) {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream(248);

        try {
            new ObjectOutputStream(bOut).writeObject(sm);
        } catch (IOException ex) {
            Logger.getLogger(ServerConnection.class.getName()).log(Level.SEVERE, null, ex);
        }

        byte[] data = bOut.toByteArray();

        for (int i : targets) {
            //br.ufsc.das.tom.util.Logger.println("(ServersCommunicationLayer.send) Sending msg to replica "+i);
            try {
                if (i == me) {
                    inQueue.put(sm);
                } else {
                    connections[i].send(data);
                }
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
        //br.ufsc.das.tom.util.Logger.println("(ServersCommunicationLayer.send) Finished sending messages to replicas");
    }

    public void shutdown() {
        doWork = false;

        for (int i = 0; i < connections.length; i++) {
            if (connections[i] != null) {
                connections[i].shutdown();
            }
        }
    }

    @Override
    public void run() {
        while (doWork) {
            try {
                Socket newSocket = serverSocket.accept();
                ServersCommunicationLayer.setSocketOptions(newSocket);
                int remoteId = new DataInputStream(newSocket.getInputStream()).readInt();
                if (remoteId >= 0 && remoteId < connections.length) {
                    if (connections[remoteId] == null) {
                        //first time that this connection is being established
                        connections[remoteId] = new ServerConnection(conf, newSocket, remoteId, inQueue);
                    } else {
                        //reconnection
                        connections[remoteId].reconnect(newSocket);
                    }
                } else {
                    newSocket.close();
                }
            } catch (SocketTimeoutException ex) {
                //timeout on the accept... do nothing
            } catch (IOException ex) {
                Logger.getLogger(ServersCommunicationLayer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        try {
            serverSocket.close();
        } catch (IOException ex) {
            Logger.getLogger(ServersCommunicationLayer.class.getName()).log(Level.SEVERE, null, ex);
        }

        Logger.getLogger(ServersCommunicationLayer.class.getName()).log(Level.INFO, "Server communication layer stoped.");
    }

    public static void setSocketOptions(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (SocketException ex) {
            Logger.getLogger(ServersCommunicationLayer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public String toString() {
        String str = "inQueue="+inQueue.toString();

        for(int i=0; i<connections.length; i++) {
            if(connections[i] != null) {
                str += ", connections["+i+"]: outQueue="+connections[i].outQueue;
            }
        }

        return str;
    }
}