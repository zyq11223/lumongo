package org.lumongo.admin;

import com.google.protobuf.ServiceException;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.lumongo.admin.help.LumongoHelpFormatter;
import org.lumongo.client.command.ClearIndex;
import org.lumongo.client.command.DeleteIndex;
import org.lumongo.client.command.GetFields;
import org.lumongo.client.command.GetIndexes;
import org.lumongo.client.command.GetMembers;
import org.lumongo.client.command.GetNumberOfDocs;
import org.lumongo.client.command.OptimizeIndex;
import org.lumongo.client.config.LumongoPoolConfig;
import org.lumongo.client.pool.LumongoWorkPool;
import org.lumongo.client.result.ClearIndexResult;
import org.lumongo.client.result.DeleteIndexResult;
import org.lumongo.client.result.GetFieldsResult;
import org.lumongo.client.result.GetIndexesResult;
import org.lumongo.client.result.GetMembersResult;
import org.lumongo.client.result.GetNumberOfDocsResult;
import org.lumongo.client.result.OptimizeIndexResult;
import org.lumongo.cluster.message.Lumongo.LMMember;
import org.lumongo.cluster.message.Lumongo.SegmentCountResponse;
import org.lumongo.util.LogUtil;

import java.io.IOException;
import java.util.Arrays;

public class IndexAdmin {

	public static void main(String[] args) throws Exception {
		LogUtil.loadLogConfig();

		OptionParser parser = new OptionParser();
		OptionSpec<String> addressArg = parser.accepts(AdminConstants.ADDRESS).withRequiredArg().defaultsTo("localhost").describedAs("Lumongo server address");
		OptionSpec<Integer> portArg = parser.accepts(AdminConstants.PORT).withRequiredArg().ofType(Integer.class).defaultsTo(32191)
				.describedAs("Lumongo external port");
		OptionSpec<String> indexArg = parser.accepts(AdminConstants.INDEX).withRequiredArg().describedAs("Index to perform action");
		OptionSpec<Command> commandArg = parser.accepts(AdminConstants.COMMAND).withRequiredArg().ofType(Command.class).required()
				.describedAs("Command to run " + Arrays.toString(Command.values()));

		int exitCode = 0;
		LumongoWorkPool lumongoWorkPool = null;

		try {
			OptionSet options = parser.parse(args);

			Command command = options.valueOf(commandArg);
			String index = options.valueOf(indexArg);
			String address = options.valueOf(addressArg);
			int port = options.valueOf(portArg);

			LumongoPoolConfig lumongoPoolConfig = new LumongoPoolConfig();
			lumongoPoolConfig.addMember(address, port);
			lumongoWorkPool = new LumongoWorkPool(lumongoPoolConfig);

			if (Command.getCount.equals(command)) {
				if (index == null) {
					throw new IllegalArgumentException(AdminConstants.INDEX + " is required for " + command.toString());
				}

				GetNumberOfDocsResult response = lumongoWorkPool.execute(new GetNumberOfDocs(index));
				System.out.println("Segments:\n" + response.getSegmentCountResponseCount());
				System.out.println("Count:\n" + response.getNumberOfDocs());
				for (SegmentCountResponse scr : response.getSegmentCountResponses()) {
					System.out.println("Segment " + scr.getSegmentNumber() + " Count:\n" + scr.getNumberOfDocs());
				}
			}
			else if (Command.getFields.equals(command)) {
				if (index == null) {
					throw new IllegalArgumentException(AdminConstants.INDEX + " is required for " + command.toString());
				}

				GetFieldsResult response = lumongoWorkPool.execute(new GetFields(index));
				response.getFieldNames().forEach(System.out::println);
			}
			else if (Command.optimize.equals(command)) {
				if (index == null) {
					throw new IllegalArgumentException(AdminConstants.INDEX + " is required for " + command.toString());
				}

				System.out.println("Optimizing Index:\n" + index);
				@SuppressWarnings("unused") OptimizeIndexResult response = lumongoWorkPool.execute(new OptimizeIndex(index));
				System.out.println("Done");
			}
			else if (Command.clear.equals(command)) {
				if (index == null) {
					throw new IllegalArgumentException(AdminConstants.INDEX + " is required for " + command.toString());
				}
				System.out.println("Clearing Index:\n" + index);
				@SuppressWarnings("unused") ClearIndexResult response = lumongoWorkPool.execute(new ClearIndex(index));
				System.out.println("Done");
			}
			else if (Command.getIndexes.equals(command)) {

				GetIndexesResult response = lumongoWorkPool.execute(new GetIndexes());
				response.getIndexNames().forEach(System.out::println);
			}
			else if (Command.getCurrentMembers.equals(command)) {

				GetMembersResult response = lumongoWorkPool.execute(new GetMembers());

				System.out.println("serverAddress\thazelcastPort\tinternalPort\texternalPort");
				for (LMMember val : response.getMembers()) {
					System.out.println(val.getServerAddress() + "\t" + val.getHazelcastPort() + "\t" + val.getInternalPort() + "\t" + val.getExternalPort());
				}
			}
			else if (Command.deleteIndex.equals(command)) {
				if (index == null) {
					throw new IllegalArgumentException(AdminConstants.INDEX + " is required for " + command.toString());
				}

				System.out.println("Deleting index <" + index + ">");
				@SuppressWarnings("unused") DeleteIndexResult response = lumongoWorkPool.execute(new DeleteIndex(index));

				System.out.println("Deleted index <" + index + ">");

			}
			else {
				System.err.println(command + " not supported");
			}

		}
		catch (OptionException | IllegalArgumentException e) {
			System.err.println("ERROR: " + e.getMessage());
			parser.formatHelpWith(new LumongoHelpFormatter());
			parser.printHelpOn(System.err);
			exitCode = 2;
		}
		catch (ServiceException | IOException e) {
			System.err.println("ERROR: " + e.getMessage());
			exitCode = 1;
		}
		finally {
			if (lumongoWorkPool != null) {
				lumongoWorkPool.shutdown();
			}
		}

		System.exit(exitCode);
	}

	public enum Command {
		clear,
		optimize,
		getCount,
		getFields,
		getIndexes,
		getCurrentMembers,
		deleteIndex
	}
}
