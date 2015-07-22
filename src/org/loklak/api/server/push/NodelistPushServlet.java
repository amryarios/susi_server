/**
 * NodelistPushServlet
 * Copyright 16.07.2015 by Dang Hai An, @zyzo
 * <p/>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program in the file lgpl21.txt
 * If not, see <http://www.gnu.org/licenses/>.
 */
package org.loklak.api.server.push;

import com.github.fge.jsonschema.core.report.LogLevel;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.loklak.api.client.ClientConnection;
import org.loklak.api.server.RemoteAccess;
import org.loklak.data.DAO;
import org.loklak.geo.LocationSource;
import org.loklak.harvester.JsonFieldConverter;
import org.loklak.harvester.JsonValidator;
import org.loklak.harvester.SourceType;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodelistPushServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        RemoteAccess.Post post = RemoteAccess.evaluate(request);
        String remoteHash = Integer.toHexString(Math.abs(post.getClientHost().hashCode()));

        // manage DoS
        if (post.isDoS_blackout()) {
            response.sendError(503, "your request frequency is too high");
            return;
        }

        String url = post.get("url", "");
        if (url == null || url.length() == 0) {
            response.sendError(400, "your request does not contain an url to your data object");
            return;
        }

        Map<String, Object> map;
        byte[] jsonText;
        try {
            jsonText = ClientConnection.download(url);
            XContentParser parser = JsonXContent.jsonXContent.createParser(jsonText);
            map = parser.map();
        } catch (Exception e) {
            response.sendError(400, "error reading json file from url");
            return;
        }

        JsonValidator validator = new JsonValidator();
        ProcessingReport report = validator.validate(new String(jsonText), JsonValidator.JsonSchemaEnum.NODELIST);
        if (report.getLogLevel() == LogLevel.ERROR || report.getLogLevel() == LogLevel.FATAL) {
            response.sendError(400, "json does not conform to Freifunk nodelist schema" + report);
            return;
        }

        // push nodes
        JsonFieldConverter converter = new JsonFieldConverter();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) map.get("nodes");
        nodes = converter.convert(nodes, JsonFieldConverter.JsonConversionSchemaEnum.NODELIST_NODE);

        Map<String, Object> community = (Map<String, Object>) map.get("community");

        // prepare fields that need more complex manips than simple field mapping
        for (Map<String, Object> node : nodes) {
            node.put("source_type", SourceType.NODELIST.name());
            Map<String, Object> location = (Map) node.get("position");
            final Double longitude = Double.parseDouble((String) location.get("long"));
            final Double latitude = Double.parseDouble((String) location.get("lat"));
            List<Double> location_point = new ArrayList<>();
            location_point.add(longitude);
            location_point.add(latitude);
            node.put("location_point", location_point);
            node.put("location_mark", location_point);
            node.put("location_source", LocationSource.USER.name());
            
            Map<String, Object> user = new HashMap<>();
            user.put("screen_name", "freifunk_" + community.get("name"));
            user.put("name", community.get("name"));
            node.put("user", user);
            try {
                node.put("id_str", PushServletHelper.computeMessageId(node, node.get("id"), SourceType.NODELIST));
            } catch (Exception e) {
                DAO.log("Problem computing id : " + e.getMessage());
            }
        }
        PushReport nodePushReport = PushServletHelper.saveMessages(nodes);

        String res = PushServletHelper.printResponse(post.get("callback", ""), nodePushReport);
        response.getOutputStream().println(res);
        DAO.log(request.getServletPath()
                + " -> records = " + nodePushReport.getRecordCount()
                + ", new = " + nodePushReport.getNewCount()
                + ", known = " + nodePushReport.getKnownCount()
                + ", from host hash " + remoteHash);
    }
}
