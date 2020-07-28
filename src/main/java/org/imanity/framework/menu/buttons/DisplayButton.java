package org.imanity.framework.menu.buttons;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.imanity.framework.menu.Button;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

@AllArgsConstructor
@Getter
@Setter
public class DisplayButton extends org.imanity.framework.menu.Button {

	private ItemStack itemStack;
	private boolean cancel;

	@Override
	public ItemStack getButtonItem(Player player) {
		if (this.itemStack == null) {
			return new ItemStack(Material.AIR);
		} else {
			return this.itemStack;
		}
	}

	@Override
	public boolean shouldCancel(Player player, int slot, ClickType clickType) {
		return this.cancel;
	}

}