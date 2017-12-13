package gov.nysenate.openleg.processor.bill.sponsor;

import gov.nysenate.openleg.model.base.SessionYear;
import gov.nysenate.openleg.model.bill.*;
import gov.nysenate.openleg.model.entity.Chamber;
import gov.nysenate.openleg.model.entity.SessionMember;
import gov.nysenate.openleg.model.process.DataProcessUnit;
import gov.nysenate.openleg.model.sourcefiles.sobi.SobiFragment;
import gov.nysenate.openleg.model.sourcefiles.sobi.SobiFragmentType;
import gov.nysenate.openleg.processor.base.AbstractDataProcessor;
import gov.nysenate.openleg.processor.base.ParseError;
import gov.nysenate.openleg.processor.sobi.SobiProcessor;
import gov.nysenate.openleg.util.XmlHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is responsible for Processing the Sponsor Sobi Fragments
 *
 * Created by Robert Bebber on 2/22/17.
 */
@Service
public class XmlLDSponProcessor extends AbstractDataProcessor implements SobiProcessor {

    private static final Logger logger = LoggerFactory.getLogger(XmlLDSponProcessor.class);

    protected static final Pattern rulesSponsorPattern =
            Pattern.compile("RULES (?:COM )?\\(?([a-zA-Z-']+)( [A-Z])?\\)?(.*)");

    /** The format for program info lines. */
    protected static final Pattern programInfoPattern = Pattern.compile("(\\d+)\\s+(.+)");

    @Autowired
    private XmlHelper xmlHelper;

    public XmlLDSponProcessor() {
    }

    @Override
    public void init() {
        initBase();
    }

    @Override
    public SobiFragmentType getSupportedType() {
        return SobiFragmentType.LDSPON;
    }

    /**
     * This method gets the specific identifiers for the fragment and then based on the prime tag, the method will
     * call separate methods depending on if the prime(sponsor) is a Budget Bill, Rule, or a standard sponsor.
     *
     * @param sobiFragment This is the fragment being worked on during this process
     */
    @Override
    public void process(SobiFragment sobiFragment) {
        logger.info("Processing Sponsor...");
        LocalDateTime date = sobiFragment.getPublishedDateTime();
        logger.info("Processing " + sobiFragment.getFragmentId() + " (xml file).");
        DataProcessUnit unit = createProcessUnit(sobiFragment);
        try {
            final Document doc = xmlHelper.parse(sobiFragment.getText());
            final Node billTextNode = xmlHelper.getNode("sponsor_data", doc);

            final Integer sessyr = xmlHelper.getInteger("@sessyr", billTextNode);
            final String sponsorhse = xmlHelper.getString("@billhse", billTextNode).trim();
            final Integer sponsorno = xmlHelper.getInteger("@billno", billTextNode);
            final String action = xmlHelper.getString("@action", billTextNode).trim();
            final String prime = xmlHelper.getString("prime", billTextNode).trim();
            final String coprime = xmlHelper.getString("co-prime", billTextNode).trim();
            final String multi = xmlHelper.getString("multi", billTextNode).trim();

            if (sponsorno == 0) // if it is a LDBC error
                return;
            Bill baseBill = getOrCreateBaseBill(sobiFragment.getPublishedDateTime(), new BillId(sponsorhse +
                    sponsorno, sessyr), sobiFragment);
            BillSponsor billSponsor = baseBill.getSponsor();
            Chamber chamber = baseBill.getBillType().getChamber();
            BillAmendment amendment = baseBill.getAmendment(baseBill.getActiveVersion());

            if (action.equals("remove")) {
                removeProcess(amendment, baseBill);
            } else {
                if (prime.contains("BUDGET BILL")) {
                    BillSponsor billSponsor1 = new BillSponsor();
                    billSponsor1.setMember(null);
                    billSponsor1.setBudget(true);
                    baseBill.setSponsor(billSponsor1);
                } else {
                    String sponsor = "";
                    if (prime.contains("RULES")) {
                        BillSponsor billSponsor1 = new BillSponsor();
                        billSponsor1.setRules(true);
                        Matcher rules = rulesSponsorPattern.matcher(prime);
                        if (!"RULES COM".equals(prime) && rules.matches()) {
                            String primeSponsor = rules.group(1) + ((rules.group(2) != null) ? rules.group(2) : "");
                            billSponsor1.setMember(getMemberFromShortName(primeSponsor, SessionYear.of(sessyr), chamber));
                        }
                        else {
                            billSponsor1.setMember(null);
                        }
                        baseBill.setSponsor(billSponsor1);

                    } else {
                        sponsor = prime;
                        List<SessionMember> sessionMembers = getSessionMember(sponsor, baseBill.getSession(), chamber);
                        if (billSponsor != null && !sessionMembers.isEmpty()) { // if noone support the bill, but it contains the session member, it maybe means that the member added in another bill. so add her/him as the sponsor
                            baseBill.setSponsor(new BillSponsor(sessionMembers.get(0)));
                            baseBill.getSponsor().setMember(sessionMembers.get(0));
                        }
                        else if (billSponsor == null && !sessionMembers.isEmpty()){
                            baseBill.setSponsor(new BillSponsor(sessionMembers.get(0)));
                        }
                        amendment.setCoSponsors(getSessionMember(coprime, baseBill.getSession(), chamber));
                        amendment.setMultiSponsors(getSessionMember(multi, baseBill.getSession(), chamber));
                    }
                }
            }
            ArrayList<ProgramInfo> programInfos = new ArrayList<>();
            NodeList departdescs = doc.getElementsByTagName("departdesc");
            if (departdescs.getLength() > 0) {
                for (int index = 0; index < departdescs.getLength(); index++) {
                    Node programInfoNode = departdescs.item(index);
                    String programInfoString = programInfoNode.getFirstChild().getTextContent();
                    if (!programInfoString.isEmpty()) {
                        Matcher programMatcher = programInfoPattern.matcher(programInfoString);
                        if (programMatcher.find()) {
                            programInfos.add(new ProgramInfo(programMatcher.group(2), Integer.parseInt(programMatcher.group(1))));

                        }
                    }
                }
                if (programInfos.size() > 0) {
                    baseBill.setProgramInfo(programInfos.get(0));
                    baseBill.setModifiedDateTime(date);
                }
            }
            billIngestCache.set(baseBill.getBaseBillId(), baseBill, sobiFragment);
        } catch (IOException | SAXException | XPathExpressionException e) {
            throw new ParseError("Error While Parsing SponsorSobiProcessorXML", e);
        }
    }

    /**
     * This method is responsible for setting every element regarding the sponsor to empty.
     *
     * @param amendment the ammendment of the bill
     * @param bill the bill in respect to the sponsor fragement
     */
    public void removeProcess(BillAmendment amendment, Bill bill) {
        bill.setSponsor(null);
        List<SessionMember> empty1 = new ArrayList<>();
        amendment.setCoSponsors(empty1);
        List<SessionMember> empty2 = new ArrayList<>();
        amendment.setMultiSponsors(empty2);
    }

    @Override
    public void postProcess() {
        flushBillUpdates();
    }
}