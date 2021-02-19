package hancho.plugin.nukkit.naverrealtimekeyword.entities;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.level.Sound;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import hancho.plugin.nukkit.naverrealtimekeyword.NaverRealtimeKeyword;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

@Getter
@Setter
public class Keyword {
    private NaverRealtimeKeyword plugin;
    private String name;
    private int ranking;
    private HashMap<UUID, Integer> playerList = new HashMap<>();

    public Keyword(JsonElement jsonElement) {
        plugin = NaverRealtimeKeyword.instance;

        JsonObject object = jsonElement.getAsJsonObject();
        this.name = parseKeywordName(object);
        this.ranking = parseKeywordRanking(object);
    }

    public static String parseKeywordName(JsonObject jsonObject) {
        return jsonObject.get("keyword").getAsString();
    }

    public static int parseKeywordRanking(JsonObject jsonObject) {
        return jsonObject.get("rank").getAsInt();
    }

    public void addPlayer(Player player, int price) {
        this.playerList.put(player.getUniqueId(), price);
    }

    public void updateRank(JsonObject jsonObject) {
        int newRanking = parseKeywordRanking(jsonObject);
        int diffValue = Math.abs(this.getRanking() - newRanking);


        if (this.playerList.size() > 0) {
            if (newRanking > this.getRanking()) {
                fail(diffValue);
            } else if (newRanking < this.getRanking()) {
                success(diffValue);
            }
        }

        this.ranking = newRanking;
        this.playerList.clear();
    }

    public void close() {
        fail(21 - this.getRanking());
    }

    protected void success(int diff) {
        for (UUID uuid : playerList.keySet()) {
            Optional<Player> player = plugin.getServer().getPlayer(uuid);
            int inputPrice = this.getPlayerList().get(uuid);

            if (plugin.economyAPI.myMoney(uuid) < inputPrice) {
                player.ifPresent(value -> value.sendMessage("§c부정행위 감지로 실검도박이 취소되었습니다."));
                continue;
            }

            int addAmount = inputPrice * diff;

            plugin.economyAPI.addMoney(uuid, addAmount);
            player.ifPresent(value -> {
                value.sendTitle("§b승리!",
                        "키워드 :§d " + this.getName() + "§f에서 §d"
                                + diff + "§f배로 총 §d" + addAmount + "§f원 얻으셨습니다",
                        20, 60, 20);
                value.sendMessage("§l§b[ §f실검도박 §b] §f 키워드 :§d " + this.getName() + "§f에서 §d"
                        + diff + "§f배로 총 §d" + addAmount + "§f원 얻으셨습니다!");

                value.level.addSound(value, Sound.RANDOM_LEVELUP, 1, 1, value);
            });

            if (!player.isPresent()) {
                plugin.addMail(plugin.playerNameMap.get(uuid),
                        "§l§b[ §f실검도박 §b] §f 키워드 :§d " + this.getName() + "§f에서 §d"
                                + diff + "§f배로 총 §d"
                                + (addAmount) + "§f원 얻으셨습니다!");
            }
        }
        plugin.getServer().broadcastMessage(
                "§o§b[ §f실검도박 §b] §f총 §b" + playerList.size()
                        + "§f명이 키워드 \"" + this.getName() + "\" (으)로 도박에 §b"
                        + diff + "§f배로 성공하셨습니다.");
    }

    protected void fail(int diffValueAbs) {
        double diff = diffValueAbs == 1 ? 1.5 : diffValueAbs;

        for (UUID uuid : playerList.keySet()) {
            Optional<Player> player = Server.getInstance().getPlayer(uuid);
            int inputPrice = this.getPlayerList().get(uuid);

            if (plugin.economyAPI.myMoney(uuid) < inputPrice) {
                Server.getInstance().getLogger().warning(
                        uuid.toString() + "님이 돈 "
                                + plugin.economyAPI.myMoney(uuid) + "원으로 돈이 부족합니다. "
                                + "배팅 금액 : " + inputPrice);
            }

            int reduceAmount = (int) Math.min(inputPrice * diff, plugin.economyAPI.myMoney(uuid));

            plugin.economyAPI.reduceMoney(uuid, reduceAmount);
            player.ifPresent(value -> {
                value.sendTitle("§c손실",
                        "키워드 :§d " + this.getName() + "§f에서 §d-"
                                + diff + "§f배로 총 §d" + reduceAmount + "§f원 잃셨습니다",
                        20, 60, 20);
                value.sendMessage("§l§c[ §f실검도박 §c] §f 키워드 :§d " + this.getName() + "§f에서 §d-" + diff + "§f배로 총 §d"
                        + reduceAmount + "§f원 잃으셨습니다.");
                value.level.addSound(value, Sound.NOTE_BANJO, 1, 1, value);
            });

            if (!player.isPresent()) {
                plugin.addMail(plugin.playerNameMap.get(uuid),
                        "§l§c[ §f실검도박 §c] §f 키워드 :§d " + this.getName() + "§f에서 §d-"
                                + diff + "§f배로 총 §d" + reduceAmount + "§f원 잃으셨습니다.");
            }
        }
    }
}
