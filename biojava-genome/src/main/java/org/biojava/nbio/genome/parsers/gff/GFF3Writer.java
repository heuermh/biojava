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
 */
package org.biojava.nbio.genome.parsers.gff;

import org.biojava.nbio.core.sequence.*;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Scooter Willis 
 */
public class GFF3Writer {

	/**
	 * Output gff3 format for a DNA Sequence
	 * @param outputStream
	 * @param chromosomeSequenceList
	 * @throws Exception
	 */
	public void write(OutputStream outputStream, Map<String, ChromosomeSequence> chromosomeSequenceList) throws Exception {

		outputStream.write("##gff-version 3\n".getBytes());
		for (String key : chromosomeSequenceList.keySet()) {
			ChromosomeSequence chromosomeSequence = chromosomeSequenceList.get(key);
			String gff3line = "";
	//         if(source.length() == 0){
	//             Collection<GeneSequence> genes = chromosomeSequence.getGeneSequences().values();
	//             for(GeneSequence gene : genes){
	//                 source = gene.getSource();
	//                 break;
	//             }
	//         }
	//         gff3line = key + "\t" + source + "\t" + "size" + "\t" + "1" + "\t" + chromosomeSequence.getBioEnd() + "\t.\t.\t.\tName=" + key + "\r\n";
	//         outputStream.write(gff3line.getBytes());

			for (GeneSequence geneSequence : chromosomeSequence.getGeneSequences().values()) {
				gff3line = key + "\t" + geneSequence.getSource() + "\t" + "gene" + "\t" + geneSequence.getBioBegin() + "\t" + geneSequence.getBioEnd() + "\t";
				Double score = geneSequence.getSequenceScore();
				if (score == null) {
					gff3line = gff3line + ".\t";
				} else {
					gff3line = gff3line + score + "\t";
				}
				gff3line = gff3line + geneSequence.getStrand().getStringRepresentation() + "\t";
				gff3line = gff3line + ".\t";
				gff3line = gff3line + "ID=" + geneSequence.getAccession().getID() + ";Name=" + geneSequence.getAccession().getID();
				gff3line = gff3line + getGFF3Note(geneSequence.getNotesList());
				gff3line = gff3line + "\n";
				outputStream.write(gff3line.getBytes());

				int transcriptIndex = 0;
				for (TranscriptSequence transcriptSequence : geneSequence.getTranscripts().values()) {
					transcriptIndex++;

					gff3line = key + "\t" + transcriptSequence.getSource() + "\t" + "mRNA" + "\t" + transcriptSequence.getBioBegin() + "\t" + transcriptSequence.getBioEnd() + "\t";
					score = transcriptSequence.getSequenceScore();
					if (score == null) {
						gff3line = gff3line + ".\t";
					} else {
						gff3line = gff3line + score + "\t";
					}
					gff3line = gff3line + transcriptSequence.getStrand().getStringRepresentation() + "\t";
					gff3line = gff3line + ".\t";
					String id = geneSequence.getAccession().getID() + "." + transcriptIndex;
					gff3line = gff3line + "ID=" + id + ";Parent=" + geneSequence.getAccession().getID() + ";Name=" + id;
					gff3line = gff3line + getGFF3Note(transcriptSequence.getNotesList());

					gff3line = gff3line + "\n";
					outputStream.write(gff3line.getBytes());

					String transcriptParentName = geneSequence.getAccession().getID() + "." + transcriptIndex;
					ArrayList<CDSSequence> cdsSequenceList = new ArrayList<>(transcriptSequence.getCDSSequences().values());
					Collections.sort(cdsSequenceList, new SequenceComparator());
					for (CDSSequence cdsSequence : cdsSequenceList) {
						gff3line = key + "\t" + cdsSequence.getSource() + "\t" + "CDS" + "\t" + cdsSequence.getBioBegin() + "\t" + cdsSequence.getBioEnd() + "\t";
						score = cdsSequence.getSequenceScore();
						if (score == null) {
							gff3line = gff3line + ".\t";
						} else {
							gff3line = gff3line + score + "\t";
						}
						gff3line = gff3line + cdsSequence.getStrand().getStringRepresentation() + "\t";
						gff3line = gff3line + cdsSequence.getPhase() + "\t";
						gff3line = gff3line + "ID=" + cdsSequence.getAccession().getID() + ";Parent=" + transcriptParentName;
						gff3line = gff3line + getGFF3Note(cdsSequence.getNotesList());

						gff3line = gff3line + "\n";
						outputStream.write(gff3line.getBytes());
					}

				}
			}

		}

	}

	private String getGFF3Note(List<String> notesList) {
		String notes = "";

		if (notesList.size() > 0) {
			notes = ";Note=";
			int noteindex = 1;
			for (String note : notesList) {
				notes = notes + note;
				if (noteindex < notesList.size() - 1) {
					notes = notes + " ";
				}
			}

		}
		return notes;
	}

}
