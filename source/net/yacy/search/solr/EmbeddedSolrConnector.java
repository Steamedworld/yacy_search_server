/**
 *  EmbeddedSolrConnector
 *  Copyright 2012 by Michael Peter Christen
 *  First released 21.06.2012 at http://yacy.net
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


package net.yacy.search.solr;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.ParserConfigurationException;

import net.yacy.cora.services.federated.solr.AbstractSolrConnector;
import net.yacy.cora.services.federated.solr.SolrConnector;
import net.yacy.kelondro.logging.Log;

import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreContainer;
import org.xml.sax.SAXException;

import com.google.common.io.Files;

public class EmbeddedSolrConnector extends AbstractSolrConnector implements SolrConnector {

    private final CoreContainer core;
    private final static String[] confFiles = {"solrconfig.xml", "schema.xml", "stopwords.txt", "synonyms.txt", "protwords.txt", "currency.xml", "elevate.xml", "lang/"};
    //private final static String[] confFiles = {"solrconfig.xml", "schema.xml", "stopwords.txt", "synonyms.txt", "protwords.txt", "currency.xml", "elevate.xml", "lang/"};

    public EmbeddedSolrConnector(File storagePath, File solr_config) throws IOException {
        super();
        // copy the solrconfig.xml to the storage path
        File conf = new File(storagePath, "conf");
        conf.mkdirs();
        File source, target;
        for (String cf: confFiles) {
            source = new File(solr_config, cf);
            if (source.isDirectory()) {
                target = new File(conf, cf);
                target.mkdirs();
                for (String cfl: source.list()) {
                    Files.copy(new File(source, cfl), new File(target, cfl));
                }
            } else {
                target = new File(conf, cf);
                target.getParentFile().mkdirs();
                Files.copy(source, target);
            }
        }
        try {
            this.core = new CoreContainer(storagePath.getAbsolutePath(), new File(solr_config, "solr.xml"));
        } catch (ParserConfigurationException e) {
            throw new IOException(e.getMessage(), e);
        } catch (SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
        super.init(new EmbeddedSolrServer(this.core, "collection1"));
    }

    @Override
    public void close() {
        super.close();
        this.core.shutdown();
    }

    public static void main(String[] args) {
        File solr_config = new File("defaults/solr");
        File storage = new File("DATA/INDEX/webportal/SEGMENTS/text/solr/");
        storage.mkdirs();
        try {
            EmbeddedSolrConnector solr = new EmbeddedSolrConnector(storage, solr_config);
            solr.close();
        } catch (IOException e) {
            Log.logException(e);
        }

    }

}
