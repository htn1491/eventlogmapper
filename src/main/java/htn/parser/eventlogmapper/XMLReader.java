/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package htn.parser.eventlogmapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 *
 * @author Admin
 */
@Component
public class XMLReader {

    private static final Logger log = LogManager.getLogger(XMLReader.class);

    @Value("${eventlogs_location}")
    private String eventlogsLocation;

    @Value("${chatlogs_export_location}")
    private String chatlogExportLocation;
    
    @Autowired
    private ApplicationContext appContext;

    private final Set<String> handledFiles = new HashSet<>();

    private DocumentBuilder documentBuilder;
    
    private void shutdown(int pExitCode) {
            int exitCode = SpringApplication.exit(appContext, (ExitCodeGenerator) () -> pExitCode);

            System.exit(exitCode);
    }

    @PostConstruct
    public void construct() {
        if(!chatlogExportLocation.endsWith("\\") && !chatlogExportLocation.endsWith("/")) {
            log.error("Configuration chatlogs_export_location must end with a slash");
            shutdown(4);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            documentBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            log.error("No document Reader instantiate...", ex);
            shutdown(2);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void readFiles() {

        XPath xPath = XPathFactory.newInstance().newXPath();

        if (Files.notExists(Paths.get(eventlogsLocation))) {
            log.error("Eventlogs location " + eventlogsLocation + " does not exist or is not readable!");
            shutdown(1);
        }

        File dir = new File(eventlogsLocation);
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File file : directoryListing) {

//            Files.walk(Paths.get(eventlogsLocation)).forEach(file -> {
                try {
                    
                    if(!file.getAbsolutePath().endsWith(".xml")) {
                        //Skip not xml files
                        continue;
                    }

                    if (handledFiles.contains(file.getAbsolutePath())) {
                        //Skip already read files
                        continue;
                    }
                    
                    log.info("Handling file "+file.getAbsolutePath());

                    handledFiles.add(file.getAbsolutePath());

                    String targetFilePath = chatlogExportLocation + file.getName() + ".chatlog";

                    if (Files.exists(Paths.get(targetFilePath))) {
                        log.debug("Chatlogfile " + targetFilePath + " for file " + file.getAbsolutePath() + " already exists! Skipping");
                        continue;
                    }

                    FileInputStream fis = new FileInputStream(file);
                    StringBuilder xmlStringBuilder = new StringBuilder();
                    try ( BufferedReader br = new BufferedReader(new InputStreamReader(fis))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            xmlStringBuilder.append(line).append("\n");
                        }
                    }

                    ByteArrayInputStream input = new ByteArrayInputStream(xmlStringBuilder.toString().getBytes("ISO-8859-1"));
                    if (documentBuilder == null) {
                        log.error("DocumentBuilder not instantiated");
                        shutdown(3);;
                    }

                    try {
                        if (!xmlStringBuilder.toString().contains("</bf:log>")) {
                            handledFiles.remove(file.getAbsolutePath());
                            log.info("File is corrupt or round has not finished yet, so file doesn't end with </bf:log>! Handling file again in next interval!");
                            continue;
                        }
                        Document doc = documentBuilder.parse(input);
                        Element docEl = doc.getDocumentElement();
                        String timestampStr = docEl.getAttribute("timestamp");
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmm");
                        long unixTimestampMillis = 0;
                        try {
                            Date dt = sdf.parse(timestampStr);
                            unixTimestampMillis = dt.getTime();
                        } catch (ParseException e) {
                            log.warn("Error parsing timestamp of timestampStr with format yyyyMMdd_HHmm! Using 0-based timestamp", e);
                        }
                        Map<Integer, PlayerModel> playerMap = new HashMap<>();
//                        NodeList nodeList = doc.getElementsByTagName("bf:log");
                        List<ChatModel> chatModels = new ArrayList<>();
                        NodeList nodeList = (NodeList) xPath.compile("/*/*/*[name()='bf:event']").evaluate(doc, XPathConstants.NODESET);
                        for (int i = 0; i < nodeList.getLength(); i++) {
                            Element node = (Element) nodeList.item(i);

                            switch (node.getAttribute("name")) {
                                case "createPlayer":
                                    PlayerModel playerModel = new PlayerModel();

                                    NodeList subNodeListPlayer = (NodeList) node.getElementsByTagName("bf:param");
                                    for (int j = 0; j < subNodeListPlayer.getLength(); j++) {
                                        Element subNode = (Element) subNodeListPlayer.item(j);
                                        switch (subNode.getAttribute("name")) {
                                            case "name":
                                                playerModel.setName(subNode.getFirstChild().getNodeValue());
                                                break;
                                            case "player_id":
                                                playerModel.setPlayerId(Integer.valueOf(subNode.getFirstChild().getNodeValue()));
                                                break;
                                            case "is_ai":
                                                playerModel.setIsAi(subNode.getFirstChild().getNodeValue().equals("1"));
                                                break;
                                            case "team":
                                                playerModel.setTeam(Integer.valueOf(subNode.getFirstChild().getNodeValue()));
                                                break;
                                        }
                                    }
                                    playerMap.put(playerModel.getPlayerId(), playerModel);
                                    break;
                                case "changePlayerName":

                                    NodeList subNodeListPlayerNameChange = (NodeList) node.getElementsByTagName("bf:param");
                                    int player_id = -1;
                                    String new_name = "";
                                    for (int j = 0; j < subNodeListPlayerNameChange.getLength(); j++) {
                                        Element subNode = (Element) subNodeListPlayerNameChange.item(j);
                                        switch (subNode.getAttribute("name")) {
                                            case "name":
                                                new_name = subNode.getFirstChild().getNodeValue();
                                                break;
                                            case "player_id":
                                                player_id = Integer.valueOf(subNode.getFirstChild().getNodeValue());
                                                break;
                                        }
                                    }
                                    if(player_id >= 0) {
                                        if(playerMap.containsKey(player_id) && StringUtils.hasText(new_name)) {
                                            PlayerModel newModel = new PlayerModel();
                                            BeanUtils.copyProperties(playerMap.get(player_id), newModel);
                                            newModel.setName(new_name);
                                            playerMap.put(player_id, newModel);
                                        }
                                    }
                                    break;
                                case "chat":
                                    String elementTimestampStr = node.getAttribute("timestamp");
                                    long elementTimestampMillis = new Double(Double.valueOf(elementTimestampStr) * 1000 + unixTimestampMillis).longValue();

                                    SimpleDateFormat outputDateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
                                    String dateOutput = outputDateFormat.format(new Date(elementTimestampMillis));

                                    ChatModel chatModel = new ChatModel();
                                    chatModel.setFormattedTimestamp(dateOutput);

                                    NodeList subNodeListChat = (NodeList) node.getElementsByTagName("bf:param");
                                    for (int j = 0; j < subNodeListChat.getLength(); j++) {
                                        Element subNode = (Element) subNodeListChat.item(j);
                                        switch (subNode.getAttribute("name")) {
                                            case "player_id":
                                                int playerId = Integer.parseInt(subNode.getFirstChild().getNodeValue());
                                                if (playerMap.containsKey(playerId)) {
                                                    chatModel.setPlayerModel(playerMap.get(playerId));
                                                }
                                                break;
                                            case "team":
                                                chatModel.setTeam(Integer.valueOf(subNode.getFirstChild().getNodeValue()));
                                                break;
                                            case "text":
                                                chatModel.setText(subNode.getFirstChild().getNodeValue());
                                                break;
                                        }
                                    }
                                    chatModels.add(chatModel);
                                    break;
                                default:
                                    break;
                            }
                        }

                        File output = new File(targetFilePath);
                        FileOutputStream fos = new FileOutputStream(output);
                        

                        log.info("Writing chatlog file "+targetFilePath);
                        try ( BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos))) {
                            for (ChatModel chatModel : chatModels) {
                                try {
                                    bw.write(chatModel.getFormattedTimestamp() + " : # [" + TeamEnum.findByCode(chatModel.getTeam()).getPrintValue() + "] " + (chatModel.getPlayerModel() == null ? "unknown" : chatModel.getPlayerModel().getName()) + ": " + chatModel.getText());
                                    bw.newLine();
                                } catch (IOException e) {
                                    log.warn("Error writing chatlog to new file " + targetFilePath + "! Is it writable?");
                                    break;
                                }
                            }
                        }
                        log.info("Write completed");
                    } catch (SAXException | XPathExpressionException e) {
                        log.warn("Unable to parse the XML for file " + file.getAbsolutePath(), e);
                    }

                } catch (IOException e) {
                    log.warn("File " + file.getPath() + " not found or readable. Skipping...", e);
                }
            }
        }
    }
}
