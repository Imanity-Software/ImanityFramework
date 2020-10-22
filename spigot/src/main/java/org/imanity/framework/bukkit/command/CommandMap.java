package org.imanity.framework.bukkit.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.util.StringUtil;
import org.imanity.framework.ImanityCommon;
import org.imanity.framework.bukkit.command.event.BukkitCommandEvent;
import org.imanity.framework.command.CommandMeta;
import org.imanity.framework.command.CommandService;
import org.imanity.framework.command.CommandEvent;
import org.imanity.framework.command.parameter.ParameterMeta;
import org.imanity.framework.plugin.service.Autowired;

import java.util.*;

final class CommandMap extends SimpleCommandMap {

	protected static final Map<UUID, String[]> parameters = new HashMap<>();

	@Autowired
	private CommandService commandService;

	public CommandMap(Server server) {
		super(server);

		ImanityCommon.registerAutowired(this);
	}

	@Override
	public List<String> tabComplete(CommandSender sender, String cmdLine) {
		if (!(sender instanceof Player)) {
			return (null);
		}

		Player player = (Player) sender;
		parameters.put(player.getUniqueId(), cmdLine.split(" "));

		try {
			int spaceIndex = cmdLine.indexOf(' ');
			Set<String> completions = new HashSet<>();

			boolean doneHere = false;

			CommandLoop:
			for (CommandMeta command : this.commandService.getCommands()) {
				if (!command.canAccess(player)) {
					continue;
				}

				for (String alias : command.getNames()) {
					String split = alias.split(" ")[0];

					if (spaceIndex != -1) {
						split = alias;
					}

					if (StringUtil.startsWithIgnoreCase(split.trim(), cmdLine.trim()) ||
					    StringUtil.startsWithIgnoreCase(cmdLine.trim(), split.trim())) {
						if (spaceIndex == -1 && cmdLine.length() < alias.length()) {
							// Complete the command
							completions.add("/" + split.toLowerCase());
						} else if (cmdLine.toLowerCase().startsWith(alias.toLowerCase() + " ") &&
						           command.getParameters().size() > 0) {
							// Complete the params
							int paramIndex = (cmdLine.split(" ").length - alias.split(" ").length);

							// If they didn't hit space, complete the param before it.
							if (paramIndex == command.getParameters().size() || !cmdLine.endsWith(" ")) {
								paramIndex = paramIndex - 1;
							}

							if (paramIndex < 0) {
								paramIndex = 0;
							}

							ParameterMeta paramData = command.getParameters().get(paramIndex);
							String[] params = cmdLine.split(" ");

							for (String completion : this.commandService.tabCompleteParameters(player, params,
									cmdLine.endsWith(" ") ? "" : params[params.length - 1],
									paramData.getParameterClass(), paramData.getTabCompleteFlags()
							)) {
								completions.add(completion);
							}

							doneHere = true;

							break CommandLoop;
						} else {
							String halfSplitString =
									split.toLowerCase().replaceFirst(alias.split(" ")[0].toLowerCase(), "").trim();
							String[] splitString = halfSplitString.split(" ");

							String fixedAlias = splitString[splitString.length - 1].trim();
							String lastArg =
									cmdLine.endsWith(" ") ? "" : cmdLine.split(" ")[cmdLine.split(" ").length - 1];

							if (fixedAlias.length() >= lastArg.length()) {
								completions.add(fixedAlias);
							}

							doneHere = true;
						}
					}
				}
			}

			List<String> completionList = new ArrayList<>(completions);

			if (!doneHere) {
				List<String> vanillaCompletionList = super.tabComplete(sender, cmdLine);

				if (vanillaCompletionList == null) {
					vanillaCompletionList = new ArrayList<>();
				}

				for (String vanillaCompletion : vanillaCompletionList) {
					completionList.add(vanillaCompletion);
				}
			}

			Collections.sort(completionList, new Comparator<String>() {
				@Override
				public int compare(String o1, String o2) {
					return (o2.length() - o1.length());
				}
			});

			completionList.remove("w");

			return (completionList);
		} catch (Exception e) {
			e.printStackTrace();

			return (new ArrayList<>());
		} finally {
			parameters.remove(player.getUniqueId());
		}
	}

	@Override
	public boolean dispatch(CommandSender sender, String commandLine) throws CommandException {
		final String command = commandLine.substring(1);

		if (sender instanceof Player) {
			CommandMap.parameters.put(((Player) sender).getUniqueId(), command.split(" "));
		}

		CommandEvent commandEvent = new BukkitCommandEvent(sender, command);
		if (this.commandService.evalCommand(commandEvent)) {
			return true;
		}

		boolean b = super.dispatch(sender, commandLine);
		if (sender instanceof ConsoleCommandSender) {
			ServerCommandEvent event = new ServerCommandEvent(sender, commandLine);
			Bukkit.getPluginManager().callEvent(event);
			if (event.isCancelled()) return false;
			return true;
		}

		return b;
	}

}