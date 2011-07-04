/**
 *  SolrSingleConnector
 *  Copyright 2011 by Michael Peter Christen
 *  First released 14.04.2011 at http://yacy.net
 *
 *  $LastChangedDate: 2011-04-14 22:05:04 +0200 (Do, 14 Apr 2011) $
 *  $LastChangedRevision: 7654 $
 *  $LastChangedBy: orbiter $
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.cora.services.federated.solr;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.document.Document;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;


public class SolrSingleConnector {

    private final String solrurl;
    private SolrServer server;
    private final SolrScheme scheme;

    private final static int transmissionQueueCount = 4; // allow concurrent http sessions to solr
    private final static int transmissionQueueSize = 50; // number of documents that are collected until a commit is sent
    private final Worker[] transmissionWorker; // the transmission workers to solr
    private final BlockingQueue<SolrInputDocument>[] transmissionQueue; // the queues quere documents are collected
    private int transmissionRoundRobinCounter; // a rount robin counter for the transmission queues

    @SuppressWarnings("unchecked")
    public SolrSingleConnector(final String url, final SolrScheme scheme) throws IOException {
        this.solrurl = url;
        this.scheme = scheme;
        this.transmissionRoundRobinCounter = 0;
        this.transmissionQueue = new ArrayBlockingQueue[transmissionQueueCount];
        for (int i = 0; i < transmissionQueueCount; i++) {
            this.transmissionQueue[i] = new ArrayBlockingQueue<SolrInputDocument>(transmissionQueueSize);
        }
        try {
            this.server = new SolrHTTPClient(this.solrurl);
        } catch (final MalformedURLException e) {
            throw new IOException("bad connector url: " + this.solrurl);
        }
        this.transmissionWorker = new Worker[transmissionQueueCount];
        for (int i = 0; i < transmissionQueueCount; i++) {
            this.transmissionWorker[i] = new Worker(i);
            this.transmissionWorker[i].start();
        }
    }

    private class Worker extends Thread {
        boolean shallRun;
        int idx;
        public Worker(final int i) {
            this.idx = i;
            this.shallRun = true;
        }
        public void pleaseStop() {
            this.shallRun = false;
        }
        public void run() {
            while (this.shallRun) {
                if (SolrSingleConnector.this.transmissionQueue[this.idx].size() > 0) {
                    try {
                        flushTransmissionQueue(this.idx);
                    } catch (final IOException e) {
                        Log.logSevere("SolrSingleConnector", "flush Transmission failed in worker", e);
                        continue;
                    }
                } else {
                    try {Thread.sleep(1000);} catch (final InterruptedException e) {}
                }
            }
            try {
                flushTransmissionQueue(this.idx);
            } catch (final IOException e) {}
        }
    }

    public void close() {
        for (int i = 0; i < transmissionQueueCount; i++) {
            if (this.transmissionWorker[i].isAlive()) {
                this.transmissionWorker[i].pleaseStop();
                try {this.transmissionWorker[i].join();} catch (final InterruptedException e) {}
            }
        }
        for (int i = 0; i < transmissionQueueCount; i++) {
            try {
                flushTransmissionQueue(i);
            } catch (final IOException e) {}
        }
    }

    /**
     * delete everything in the solr index
     * @throws IOException
     */
    public void clear() throws IOException {
        try {
            this.server.deleteByQuery("*:*");
            this.server.commit();
        } catch (final SolrServerException e) {
            throw new IOException(e);
        }
    }

    public void delete(final String id) throws IOException {
        try {
            this.server.deleteById(id);
        } catch (final SolrServerException e) {
            throw new IOException(e);
        }
    }

    public void delete(final List<String> ids) throws IOException {
        try {
            this.server.deleteById(ids);
        } catch (final SolrServerException e) {
            throw new IOException(e);
        }
    }

    public void add(final File file, final String solrId) throws IOException {
        final ContentStreamUpdateRequest up = new ContentStreamUpdateRequest("/update/extract");
        up.addFile(file);
        up.setParam("literal.id", solrId);
        up.setParam("uprefix", "attr_");
        up.setParam("fmap.content", "attr_content");
        //up.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);
        try {
            this.server.request(up);
            this.server.commit();
        } catch (final SolrServerException e) {
            throw new IOException(e);
        }
    }

    public void add(final String id, final ResponseHeader header, final Document doc) throws IOException {
        add(this.scheme.yacy2solr(id, header, doc));
    }

    protected void add(final SolrInputDocument solrdoc) throws IOException {
        int thisrrc = this.transmissionRoundRobinCounter;
        int nextrrc = thisrrc++;
        if (nextrrc >= transmissionQueueCount) nextrrc = 0;
        this.transmissionRoundRobinCounter = nextrrc;
        if (this.transmissionWorker[thisrrc].isAlive()) {
            this.transmissionQueue[thisrrc].offer(solrdoc);
        } else {
            if (this.transmissionQueue[thisrrc].size() > 0) flushTransmissionQueue(thisrrc);
            final Collection<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
            docs.add(solrdoc);
            addSolr(docs);
        }
    }

    protected void addSolr(final Collection<SolrInputDocument> docs) throws IOException {
        try {
            this.server.add(docs);
            this.server.commit();
            /* To immediately commit after adding documents, you could use:
                  UpdateRequest req = new UpdateRequest();
                  req.setAction( UpdateRequest.ACTION.COMMIT, false, false );
                  req.add( docs );
                  UpdateResponse rsp = req.process( server );
             */
        } catch (final SolrServerException e) {
            throw new IOException(e);
        }
    }

    public void err(final DigestURI digestURI, final String failReason, final int httpstatus) throws IOException {

            final SolrInputDocument solrdoc = new SolrInputDocument();
            solrdoc.addField("id", ASCII.String(digestURI.hash()));
            solrdoc.addField("sku", digestURI.toNormalform(true, false), 3.0f);
            final InetAddress address = Domains.dnsResolve(digestURI.getHost());
            if (address != null) solrdoc.addField("ip_s", address.getHostAddress());
            if (digestURI.getHost() != null) solrdoc.addField("host_s", digestURI.getHost());

            // path elements of link
            final String path = digestURI.getPath();
            if (path != null) {
                final String[] paths = path.split("/");
                if (paths.length > 0) solrdoc.addField("attr_paths", paths);
            }

            solrdoc.addField("failreason_t", failReason);
            solrdoc.addField("httpstatus_i", httpstatus);

            add(solrdoc);
    }

    private void flushTransmissionQueue(final int idx) throws IOException {
        final Collection<SolrInputDocument> c = new ArrayList<SolrInputDocument>();
        while (this.transmissionQueue[idx].size() > 0) {
            try {
                c.add(this.transmissionQueue[idx].take());
            } catch (final InterruptedException e) {
                continue;
            }
        }
        addSolr(c);
    }


    /**
     * get a query result from solr
     * to get all results set the query String to "*:*"
     * @param querystring
     * @throws IOException
     */
    public SolrDocumentList get(final String querystring, final int offset, final int count) throws IOException {
        // construct query
        final SolrQuery query = new SolrQuery();
        query.setQuery(querystring);
        query.setRows(count);
        query.setStart(offset);
        //query.addSortField( "price", SolrQuery.ORDER.asc );

        // query the server
        //SearchResult result = new SearchResult(count);
        try {
            final QueryResponse rsp = this.server.query( query );
            final SolrDocumentList docs = rsp.getResults();
            return docs;
            // add the docs into the YaCy search result container
            /*
            for (SolrDocument doc: docs) {
                result.put(element)
            }
            */
        } catch (final SolrServerException e) {
            throw new IOException(e);
        }

        //return result;
    }

    public static void main(final String args[]) {
        SolrSingleConnector solr;
        try {
            solr = new SolrSingleConnector("http://127.0.0.1:8983/solr", new SolrScheme());
            solr.clear();
            final File exampleDir = new File("/Data/workspace2/yacy/test/parsertest/");
            long t, t0, a = 0;
            int c = 0;
            for (final String s: exampleDir.list()) {
                if (s.startsWith(".")) continue;
                t = System.currentTimeMillis();
                solr.add(new File(exampleDir, s), s);
                t0 = (System.currentTimeMillis() - t);
                a += t0;
                c++;
                System.out.println("pushed file " + s + " to solr, " + t0 + " milliseconds");
            }
            System.out.println("pushed " + c + " files in " + a + " milliseconds, " + (a / c) + " milliseconds average; " + (60000 / a * c) + " PPM");
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

}
