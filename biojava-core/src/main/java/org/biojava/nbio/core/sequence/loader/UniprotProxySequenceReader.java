/*
 *                    BioJava development code
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  If you do not have a copy,
 * see:
 *
 *      http://www.gnu.org/copyleft/lesser.html
 *
 * Copyright for this code is held jointly by the individual
 * authors.  These should be listed in @author doc comments.
 *
 * For more information on the BioJava project and its aims,
 * or to join the biojava-l mailing list, visit the home page
 * at:
 *
 *      http://www.biojava.org/
 *
 * Created on 01-21-2010
 *
 * @auther Scooter Willis
 *
 */
package org.biojava.nbio.core.sequence.loader;

import org.biojava.nbio.core.exceptions.CompoundNotFoundException;
import org.biojava.nbio.core.sequence.AccessionID;
import org.biojava.nbio.core.sequence.DataSource;
import org.biojava.nbio.core.sequence.ProteinSequence;
import org.biojava.nbio.core.sequence.Strand;
import org.biojava.nbio.core.sequence.compound.AminoAcidCompound;
import org.biojava.nbio.core.sequence.compound.AminoAcidCompoundSet;
import org.biojava.nbio.core.sequence.features.DBReferenceInfo;
import org.biojava.nbio.core.sequence.features.DatabaseReferenceInterface;
import org.biojava.nbio.core.sequence.features.FeaturesKeyWordInterface;
import org.biojava.nbio.core.sequence.storage.SequenceAsStringHelper;
import org.biojava.nbio.core.sequence.template.*;
import org.biojava.nbio.core.util.Equals;
import org.biojava.nbio.core.util.XMLHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.*;
import java.util.regex.Pattern;

/**
 *
 * Pass in a Uniprot ID and this ProxySequenceReader when passed to a ProteinSequence will get the sequence data and other data elements
 * associated with the ProteinSequence by Uniprot. This is an example of how to map external databases of proteins and features to the BioJava3
 * ProteinSequence.
 * Important to call @see setUniprotDirectoryCache to allow caching of XML files so they don't need to be reloaded each time. Does
 * not manage cache.
 * @param <C>
 */
public class UniprotProxySequenceReader<C extends Compound> implements ProxySequenceReader<C>, FeaturesKeyWordInterface, DatabaseReferenceInterface {

	private final static Logger logger = LoggerFactory.getLogger(UniprotProxySequenceReader.class);

	/*
	 * Taken from http://www.uniprot.org/help/accession_numbers
	 */
	private static final String SPID_PATTERN = "[OPQ][0-9][A-Z0-9]{3}[0-9]";
	private static final String TREMBLID_PATTERN = "[A-NR-Z][0-9]([A-Z][A-Z0-9]{2}[0-9]){1,2}";
	public static final Pattern UP_AC_PATTERN = Pattern.compile("(" + SPID_PATTERN + "|" + TREMBLID_PATTERN + ")");

	public static final String DEFAULT_UNIPROT_BASE_URL = "https://www.uniprot.org";

	private static String uniprotbaseURL = DEFAULT_UNIPROT_BASE_URL;
	private static String uniprotDirectoryCache = null;
	private String sequence;
	private CompoundSet<C> compoundSet;
	private List<C> parsedCompounds = new ArrayList<>();
	Document uniprotDoc;

	/**
	 * The UniProt id is used to retrieve the UniProt XML which is then parsed as a DOM object
	 * so we know everything about the protein. If an error occurs throw an exception. We could
	 * have a bad uniprot id or network error
	 * @param accession
	 * @param compoundSet
	 * @throws CompoundNotFoundException
	 * @throws IOException if problems while reading the UniProt XML
	 */
	public UniprotProxySequenceReader(String accession, CompoundSet<C> compoundSet) throws CompoundNotFoundException, IOException {
		if (!UP_AC_PATTERN.matcher(accession.toUpperCase()).matches()) {
			throw new IllegalArgumentException("Accession provided " + accession + " doesn't comply with the uniprot acession pattern.");
		}
		setCompoundSet(compoundSet);
		uniprotDoc = this.getUniprotXML(accession);
		String seq = this.getSequence(uniprotDoc);
		setContents(seq);
	}

	/**
	 * The xml is passed in as a DOM object so we know everything about the protein.
	 *  If an error occurs throw an exception. We could have a bad uniprot id
	 * @param document
	 * @param compoundSet
	 * @throws CompoundNotFoundException
	 */
	public UniprotProxySequenceReader(Document document, CompoundSet<C> compoundSet) throws CompoundNotFoundException {
		setCompoundSet(compoundSet);
		uniprotDoc = document;
		String seq = this.getSequence(uniprotDoc);
		setContents(seq);
	}
	/**
	 * The passed in xml is parsed as a DOM object so we know everything about the protein.
	 *  If an error occurs throw an exception. We could have a bad uniprot id
	 * @param xml
	 * @param compoundSet
	 * @return UniprotProxySequenceReader
	 */
	public static <C extends Compound> UniprotProxySequenceReader<C> parseUniprotXMLString(String xml, CompoundSet<C> compoundSet) {
		try {
			Document document = XMLHelper.inputStreamToDocument(new ByteArrayInputStream(xml.getBytes()));
			return new UniprotProxySequenceReader<>(document, compoundSet);
		} catch (Exception e) {
			logger.error("Exception on xml parse of: {}", xml);
		}
		return null;
	}

	@Override
	public void setCompoundSet(CompoundSet<C> compoundSet) {
		this.compoundSet = compoundSet;
	}

	/**
	 * Once the sequence is retrieved set the contents and make sure everything this is valid
	 * Some uniprot records contain white space in the sequence. We must strip it out so setContents doesn't fail.
	 * @param sequence
	 * @throws CompoundNotFoundException
	 */
	@Override
	public void setContents(String sequence) throws CompoundNotFoundException {
		// Horrendously inefficient - pretty much the way the old BJ did things.
		// TODO Should be optimised.
		// NOTE This chokes on whitespace in the sequence, so whitespace is stripped
		this.sequence = sequence.replaceAll("\\s", "").trim();
		this.parsedCompounds.clear();
		for (int i = 0; i < this.sequence.length();) {
			String compoundStr = null;
			C compound = null;
			for (int compoundStrLength = 1; compound == null && compoundStrLength <= compoundSet.getMaxSingleCompoundStringLength(); compoundStrLength++) {
				compoundStr = this.sequence.substring(i, i + compoundStrLength);
				compound = compoundSet.getCompoundForString(compoundStr);
			}
			if (compound == null) {
				throw new CompoundNotFoundException("Compound "+compoundStr+" not found");
			} else {
				i += compoundStr.length();
			}
			this.parsedCompounds.add(compound);
		}
	}

	/**
	 * The sequence length
	 * @return
	 */
	@Override
	public int getLength() {
		return this.parsedCompounds.size();
	}

	/**
	 *
	 * @param position
	 * @return
	 */
	@Override
	public C getCompoundAt(int position) {
		return this.parsedCompounds.get(position - 1);
	}

	/**
	 *
	 * @param compound
	 * @return
	 */
	@Override
	public int getIndexOf(C compound) {
		return this.parsedCompounds.indexOf(compound) + 1;
	}

	/**
	 *
	 * @param compound
	 * @return
	 */
	@Override
	public int getLastIndexOf(C compound) {
		return this.parsedCompounds.lastIndexOf(compound) + 1;
	}

	/**
	 *
	 * @return
	 */
	@Override
	public String toString() {
		return getSequenceAsString();
	}

	/**
	 *
	 * @return
	 */
	@Override
	public String getSequenceAsString() {
		return sequence;
	}

	/**
	 *
	 * @return
	 */
	@Override
	public List<C> getAsList() {
		return this.parsedCompounds;
	}

	@Override
	public boolean equals(Object o){

		if(! Equals.classEqual(this, o)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Sequence<C> other = (Sequence<C>)o;
		if ( other.getCompoundSet() != getCompoundSet())
			return false;

		List<C> rawCompounds = getAsList();
		List<C> otherCompounds = other.getAsList();

		if ( rawCompounds.size() != otherCompounds.size())
			return false;

		for (int i = 0 ; i < rawCompounds.size() ; i++){
			Compound myCompound = rawCompounds.get(i);
			Compound otherCompound = otherCompounds.get(i);
			if ( ! myCompound.equalsIgnoreCase(otherCompound))
				return false;
		}
		return true;
	}

	@Override
	public int hashCode(){
		String s = getSequenceAsString();
		return s.hashCode();
	}

	/**
	 *
	 * @return
	 */
	@Override
	public SequenceView<C> getInverse() {
		return SequenceMixin.inverse(this);
	}

	/**
	 *
	 * @param bioBegin
	 * @param bioEnd
	 * @param strand
	 * @return
	 */
	public String getSequenceAsString(Integer bioBegin, Integer bioEnd, Strand strand) {
		SequenceAsStringHelper<C> sequenceAsStringHelper = new SequenceAsStringHelper<>();
		return sequenceAsStringHelper.getSequenceAsString(this.parsedCompounds, compoundSet, bioBegin, bioEnd, strand);
	}

	/**
	 *
	 * @param bioBegin
	 * @param bioEnd
	 * @return
	 */
	@Override
	public SequenceView<C> getSubSequence(final Integer bioBegin, final Integer bioEnd) {
		return new SequenceProxyView<>(UniprotProxySequenceReader.this, bioBegin, bioEnd);
	}

	/**
	 *
	 * @return
	 */
	@Override
	public Iterator<C> iterator() {
		return this.parsedCompounds.iterator();
	}

	/**
	 *
	 * @return
	 */
	@Override
	public CompoundSet<C> getCompoundSet() {
		return compoundSet;
	}

	/**
	 *
	 * @return
	 */
	@Override
	public AccessionID getAccession() {
		AccessionID accessionID = new AccessionID();
		if (uniprotDoc == null) {
			return accessionID;
		}
		try {
			Element uniprotElement = uniprotDoc.getDocumentElement();
			Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
			Element nameElement = XMLHelper.selectSingleElement(entryElement, "name");
			accessionID = new AccessionID(nameElement.getTextContent(), DataSource.UNIPROT);
		} catch (XPathExpressionException e) {
			logger.error("Exception: ", e);
		}
		return accessionID;
	}

	/**
	 * Pull uniprot accessions associated with this sequence
	 * @return
	 * @throws XPathExpressionException
	 */
	public List<AccessionID> getAccessions() throws XPathExpressionException {
		List<AccessionID> accessionList = new ArrayList<>();
		if (uniprotDoc == null) {
			return accessionList;
		}
		Element uniprotElement = uniprotDoc.getDocumentElement();
		Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
		List<Element> keyWordElementList = XMLHelper.selectElements(entryElement, "accession");
		for (Element element : keyWordElementList) {
			AccessionID accessionID = new AccessionID(element.getTextContent(), DataSource.UNIPROT);
			accessionList.add(accessionID);
		}

		return accessionList;
	}

	/**
	 * Pull uniprot protein aliases associated with this sequence
	 * Provided for backwards compatibility now that we support both
	 * gene and protein aliases via separate methods.
	 * @return
	 * @throws XPathExpressionException
	 */
	public List<String> getAliases() throws XPathExpressionException {

		return getProteinAliases();
	}
	/**
	 * Pull uniprot protein aliases associated with this sequence
	 * @return
	 * @throws XPathExpressionException
	 */
	public List<String> getProteinAliases() throws XPathExpressionException {
		List<String> aliasList = new ArrayList<>();
		if (uniprotDoc == null) {
			return aliasList;
		}
		Element uniprotElement = uniprotDoc.getDocumentElement();
		Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
		Element proteinElement = XMLHelper.selectSingleElement(entryElement, "protein");
		
		List<Element> keyWordElementList;
		getProteinAliasesFromNameGroup(aliasList, proteinElement);
		
		keyWordElementList = XMLHelper.selectElements(proteinElement, "component");
		for (Element element : keyWordElementList) {
			getProteinAliasesFromNameGroup(aliasList, element);
		}

		keyWordElementList = XMLHelper.selectElements(proteinElement, "domain");
		for (Element element : keyWordElementList) {
			getProteinAliasesFromNameGroup(aliasList, element);
		}

		keyWordElementList = XMLHelper.selectElements(proteinElement, "submittedName");
		for (Element element : keyWordElementList) {
			getProteinAliasesFromNameGroup(aliasList, element);
		}

		keyWordElementList = XMLHelper.selectElements(proteinElement, "cdAntigenName");
		for (Element element : keyWordElementList) {
			String cdAntigenName = element.getTextContent();
			if(null != cdAntigenName && !cdAntigenName.trim().isEmpty()) {
				aliasList.add(cdAntigenName);
			}
		}
			
		keyWordElementList = XMLHelper.selectElements(proteinElement, "innName");
		for (Element element : keyWordElementList) {
			String cdAntigenName = element.getTextContent();
			if(null != cdAntigenName && !cdAntigenName.trim().isEmpty()) {
				aliasList.add(cdAntigenName);
			}
		}

		keyWordElementList = XMLHelper.selectElements(proteinElement, "biotechName");
		for (Element element : keyWordElementList) {
			String cdAntigenName = element.getTextContent();
			if(null != cdAntigenName && !cdAntigenName.trim().isEmpty()) {
				aliasList.add(cdAntigenName);
			}
		}

		keyWordElementList = XMLHelper.selectElements(proteinElement, "allergenName");
		for (Element element : keyWordElementList) {
			String cdAntigenName = element.getTextContent();
			if(null != cdAntigenName && !cdAntigenName.trim().isEmpty()) {
				aliasList.add(cdAntigenName);
			}
		}

		return aliasList;
	}

	/**
	 * @param aliasList
	 * @param proteinElement
	 * @throws XPathExpressionException
	 */
	private void getProteinAliasesFromNameGroup(List<String> aliasList, Element proteinElement)
			throws XPathExpressionException {
		List<Element> keyWordElementList = XMLHelper.selectElements(proteinElement, "alternativeName");
		for (Element element : keyWordElementList) {
			getProteinAliasesFromElement(aliasList, element);
		}
		
		keyWordElementList = XMLHelper.selectElements(proteinElement, "recommendedName");
		for (Element element : keyWordElementList) {
			getProteinAliasesFromElement(aliasList, element);
		}
	}

	/**
	 * @param aliasList
	 * @param element
	 * @throws XPathExpressionException
	 */
	private void getProteinAliasesFromElement(List<String> aliasList, Element element)
			throws XPathExpressionException {
		Element fullNameElement = XMLHelper.selectSingleElement(element, "fullName");
		aliasList.add(fullNameElement.getTextContent());
		Element shortNameElement = XMLHelper.selectSingleElement(element, "shortName");
		if(null != shortNameElement) {
			String shortName = shortNameElement.getTextContent();
			if(null != shortName && !shortName.trim().isEmpty()) {
				aliasList.add(shortName);
			}
		}
	}

	/**
	 * Pull uniprot gene aliases associated with this sequence
	 * @return
	 * @throws XPathExpressionException
	 */
	public List<String> getGeneAliases() throws XPathExpressionException {
		List<String> aliasList = new ArrayList<>();
		if (uniprotDoc == null) {
			return aliasList;
		}
		Element uniprotElement = uniprotDoc.getDocumentElement();
		Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
		List<Element> proteinElements = XMLHelper.selectElements(entryElement, "gene");
		for(Element proteinElement : proteinElements) {
			List<Element> keyWordElementList = XMLHelper.selectElements(proteinElement, "name");
			for (Element element : keyWordElementList) {
				aliasList.add(element.getTextContent());
			}
		}
		return aliasList;
	}

	/**
	 *
	 * @param compounds
	 * @return
	 */
	@Override
	public int countCompounds(C... compounds) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	/**
	 *
	 * @param accession
	 * @return
	 * @throws IOException
	 */
	private Document getUniprotXML(String accession) throws IOException, CompoundNotFoundException {
		StringBuilder sb = new StringBuilder();
		// try in cache
		if (uniprotDirectoryCache != null && uniprotDirectoryCache.length() > 0) {
			sb = fetchFromCache(accession);
		}

		// http://www.uniprot.org/uniprot/?query=SORBIDRAFT_03g027040&format=xml
		if (sb.length() == 0) {
			String uniprotURL = getUniprotbaseURL() + "/uniprot/" + accession.toUpperCase() + ".xml";
			logger.info("Loading: {}", uniprotURL);
			sb = fetchUniprotXML(uniprotURL);

			int index = sb.indexOf("xmlns="); //strip out name space stuff to make it easier on xpath
			if (index != -1) {
				int lastIndex = sb.indexOf(">", index);
				sb.replace(index, lastIndex, "");
			}
			if (uniprotDirectoryCache != null && uniprotDirectoryCache.length() > 0)
				writeCache(sb,accession);
		}

		logger.info("Load complete");
		try {
			//       logger.debug(sb.toString());
			Document document = XMLHelper.inputStreamToDocument(new ByteArrayInputStream(sb.toString().getBytes()));
			return document;
		} catch (SAXException | ParserConfigurationException e) {
			logger.error("Exception on xml parse of: {}", sb.toString());
		}
		return null;
	}

	private void writeCache(StringBuilder sb, String accession) throws IOException {
		File f = new File(uniprotDirectoryCache + File.separatorChar + accession + ".xml");
		try (FileWriter fw = new FileWriter(f)) {
			fw.write(sb.toString());
		}
	}

	/**
	 * Open a URL connection.
	 *
	 * Follows redirects.
	 * @param url
	 * @throws IOException
	 */
	private static HttpURLConnection openURLConnection(URL url) throws IOException {
		// This method should be moved to a utility class in BioJava 5.0

		final int timeout = 5000;
		final String useragent = "BioJava";

		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("User-Agent", useragent);
		conn.setInstanceFollowRedirects(true);
		conn.setConnectTimeout(timeout);
		conn.setReadTimeout(timeout);

		int status = conn.getResponseCode();
		while (status == HttpURLConnection.HTTP_MOVED_TEMP
				|| status == HttpURLConnection.HTTP_MOVED_PERM
				|| status == HttpURLConnection.HTTP_SEE_OTHER) {
			// Redirect!
			String newUrl = conn.getHeaderField("Location");

			if(newUrl.equals(url.toString())) {
				throw new IOException("Cyclic redirect detected at "+newUrl);
			}

			// Preserve cookies
			String cookies = conn.getHeaderField("Set-Cookie");

			// open the new connection again
			url = new URL(newUrl);
			conn.disconnect();
			conn = (HttpURLConnection) url.openConnection();
			if(cookies != null) {
				conn.setRequestProperty("Cookie", cookies);
			}
			conn.addRequestProperty("User-Agent", useragent);
			conn.setInstanceFollowRedirects(true);
			conn.setConnectTimeout(timeout);
			conn.setReadTimeout(timeout);
			conn.connect();

			status = conn.getResponseCode();

			logger.info("Redirecting from {} to {}", url, newUrl);
		}
		conn.connect();

		return conn;
	}

	private StringBuilder fetchUniprotXML(String uniprotURL)
			throws IOException, CompoundNotFoundException {

		StringBuilder sb = new StringBuilder();
		URL uniprot = new URL(uniprotURL);
		int attempt = 5;
		List<String> errorCodes = new ArrayList<>();
		while(attempt > 0) {
			HttpURLConnection uniprotConnection = openURLConnection(uniprot);
			int statusCode = uniprotConnection.getResponseCode();
			if (statusCode == HttpURLConnection.HTTP_OK) {
				BufferedReader in = new BufferedReader(
						new InputStreamReader(
						uniprotConnection.getInputStream()));
				String inputLine;

				while ((inputLine = in.readLine()) != null) {
					sb.append(inputLine);
				}
				in.close();
				return sb;
			}
			attempt--;
			errorCodes.add(String.valueOf(statusCode));
		}
		throw new RemoteException("Couldn't fetch accession from the url " + uniprotURL + " error codes on 5 attempts are " + errorCodes.toString());
	}

	/**
	 * @param key
	 * @return A string containing the contents of entry specified by key and if not found returns an empty string
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private StringBuilder fetchFromCache(String key)
			throws IOException {
		int index;
		File f = new File(uniprotDirectoryCache + File.separatorChar + key + ".xml");
		StringBuilder sb = new StringBuilder();
		if (f.exists()) {
			char[] data;
			try (FileReader fr = new FileReader(f)) {
				int size = (int) f.length();
				data = new char[size];
				fr.read(data);
			}
			sb.append(data);
			index = sb.indexOf("xmlns="); //strip out name space stuff to make it easier on xpath
			if (index != -1) {
				int lastIndex = sb.indexOf(">", index);
				sb.replace(index, lastIndex, "");
			}
		}
		return sb;
	}

	/**
	 *
	 * @param uniprotDoc
	 * @return
	 */
	private String getSequence(Document uniprotDoc)  {

		try {
			Element uniprotElement = uniprotDoc.getDocumentElement();
			Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
			Element sequenceElement = XMLHelper.selectSingleElement(entryElement, "sequence");

			String seqdata = sequenceElement.getTextContent();

			return seqdata;
		} catch (XPathExpressionException e) {
			logger.error("Problems while parsing sequence in UniProt XML: {}. Sequence will be blank.", e.getMessage());
			return "";
		}
	}

	/**
	 * The current UniProt URL to deal with caching issues. www.uniprot.org is load balanced
	 * but you can access pir.uniprot.org directly.
	 * @return the uniprotbaseURL
	 */
	public static String getUniprotbaseURL() {
		return uniprotbaseURL;
	}

	/**
	 * @param aUniprotbaseURL the uniprotbaseURL to set
	 */
	public static void setUniprotbaseURL(String aUniprotbaseURL) {
		uniprotbaseURL = aUniprotbaseURL;
	}

	/**
	 * Local directory cache of XML that can be downloaded
	 * @return the uniprotDirectoryCache
	 */
	public static String getUniprotDirectoryCache() {
		return uniprotDirectoryCache;
	}

	/**
	 * @param aUniprotDirectoryCache the uniprotDirectoryCache to set
	 */
	public static void setUniprotDirectoryCache(String aUniprotDirectoryCache) {
		File f = new File(aUniprotDirectoryCache);
		if (!f.exists()) {
			f.mkdirs();
		}
		uniprotDirectoryCache = aUniprotDirectoryCache;
	}


	/**
	 * Get the gene name associated with this sequence.
	 * @return
	 */
	public String getGeneName() {
		if (uniprotDoc == null) {
			return "";
		}
		try {
			Element uniprotElement = uniprotDoc.getDocumentElement();
			Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
			Element geneElement = XMLHelper.selectSingleElement(entryElement, "gene");
			if (geneElement == null) {
				return "";
			}
			Element nameElement = XMLHelper.selectSingleElement(geneElement, "name");
			if (nameElement == null) {
				return "";
			}
			return nameElement.getTextContent();
		} catch (XPathExpressionException e) {
			logger.error("Problems while parsing gene name in UniProt XML: {}. Gene name will be blank.",e.getMessage());
			return "";
		}
	}

	/**
	 * Get the organism name assigned to this sequence
	 * @return
	 */
	public String getOrganismName() {
		if (uniprotDoc == null) {
			return "";
		}
		try {
			Element uniprotElement = uniprotDoc.getDocumentElement();
			Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
			Element organismElement = XMLHelper.selectSingleElement(entryElement, "organism");
			if (organismElement == null) {
				return "";
			}
			Element nameElement = XMLHelper.selectSingleElement(organismElement, "name");
			if (nameElement == null) {
				return "";
			}
			return nameElement.getTextContent();
		} catch (XPathExpressionException e) {
			logger.error("Problems while parsing organism name in UniProt XML: {}. Organism name will be blank.",e.getMessage());
			return "";
		}

	}

	/**
	 * Pull UniProt key words which is a mixed bag of words associated with this sequence
	 * @return
	 */
	@Override
	public List<String> getKeyWords() {
		List<String> keyWordsList = new ArrayList<>();
		if (uniprotDoc == null) {
			return keyWordsList;
		}
		try {
			Element uniprotElement = uniprotDoc.getDocumentElement();

			Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
			List<Element> keyWordElementList = XMLHelper.selectElements(entryElement, "keyword");
			for (Element element : keyWordElementList) {
				keyWordsList.add(element.getTextContent());
			}
		} catch (XPathExpressionException e) {
			logger.error("Problems while parsing keywords in UniProt XML: {}. No keywords will be available.",e.getMessage());
			return new ArrayList<>();
		}

		return keyWordsList;
	}

	/**
	 * The Uniprot mappings to other database identifiers for this sequence
	 * @return
	 */
	@Override
	public Map<String, List<DBReferenceInfo>> getDatabaseReferences()  {
		Map<String, List<DBReferenceInfo>> databaseReferencesHashMap = new LinkedHashMap<>();
		if (uniprotDoc == null) {
			return databaseReferencesHashMap;
		}

		try {
			Element uniprotElement = uniprotDoc.getDocumentElement();
			Element entryElement = XMLHelper.selectSingleElement(uniprotElement, "entry");
			List<Element> dbreferenceElementList = XMLHelper.selectElements(entryElement, "dbReference");
			for (Element element : dbreferenceElementList) {
				String type = element.getAttribute("type");
				String id = element.getAttribute("id");
				List<DBReferenceInfo> idlist = databaseReferencesHashMap.get(type);
				if (idlist == null) {
					idlist = new ArrayList<>();
					databaseReferencesHashMap.put(type, idlist);
				}
				DBReferenceInfo dbreferenceInfo = new DBReferenceInfo(type, id);
				List<Element> propertyElementList = XMLHelper.selectElements(element, "property");
				for (Element propertyElement : propertyElementList) {
					String propertyType = propertyElement.getAttribute("type");
					String propertyValue = propertyElement.getAttribute("value");
					dbreferenceInfo.addProperty(propertyType, propertyValue);
				}

				idlist.add(dbreferenceInfo);
			}
		} catch (XPathExpressionException e) {
			logger.error("Problems while parsing db references in UniProt XML: {}. No db references will be available.",e.getMessage());
			return new LinkedHashMap<>();
		}

		return databaseReferencesHashMap;
	}
}
