package org.lumongo.server.rest;

import com.cedarsoftware.util.io.JsonWriter;
import com.mongodb.BasicDBObject;
import com.mongodb.util.JSONSerializers;
import org.bson.Document;
import org.lumongo.LumongoConstants;
import org.lumongo.cluster.message.Lumongo;
import org.lumongo.server.index.LumongoIndexManager;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

@Path(LumongoConstants.MEMBERS_URL)
public class MembersResource {

	private LumongoIndexManager indexManager;

	public MembersResource(LumongoIndexManager indexManager) {
		this.indexManager = indexManager;
	}

	@GET
	@Produces({ MediaType.APPLICATION_JSON + ";charset=utf-8" })
	public Response get(@Context Response response, @QueryParam(LumongoConstants.PRETTY) boolean pretty) {

		try {
			Lumongo.GetMembersResponse getMembersResponse = indexManager.getMembers(Lumongo.GetMembersRequest.newBuilder().build());

			org.bson.Document mongoDocument = new org.bson.Document();

			List<Document> memberObjList = new ArrayList<>();
			for (Lumongo.LMMember lmMember : getMembersResponse.getMemberList()) {
				Document memberObj = new Document();
				memberObj.put("serverAddress", lmMember.getServerAddress());
				memberObj.put("hazelcastPort", lmMember.getHazelcastPort());
				memberObj.put("internalPort", lmMember.getInternalPort());
				memberObj.put("externalPort", lmMember.getExternalPort());

				Document indexMappingObj = new Document();
				for (Lumongo.IndexMapping indexMapping : getMembersResponse.getIndexMappingList()) {

					TreeSet<Integer> segments = new TreeSet<>();
					for (Lumongo.SegmentMapping segmentMapping : indexMapping.getSegmentMappingList()) {
						if (segmentMapping.getMember().equals(lmMember)) {
							segments.add(segmentMapping.getSegmentNumber());
						}
					}

					indexMappingObj.put(indexMapping.getIndexName(), segments);
				}
				memberObj.put("indexMapping", indexMappingObj);


				memberObjList.add(memberObj);

			}

			mongoDocument.put("members", memberObjList);

			String docString = JSONSerializers.getStrict().serialize(mongoDocument);

			if (pretty) {
				docString = JsonWriter.formatJson(docString);
			}

			return Response.status(LumongoConstants.SUCCESS).entity(docString).build();

		}
		catch (Exception e) {
			return Response.status(LumongoConstants.INTERNAL_ERROR).entity("Failed to get cluster membership: " + e.getMessage()).build();
		}

	}

}