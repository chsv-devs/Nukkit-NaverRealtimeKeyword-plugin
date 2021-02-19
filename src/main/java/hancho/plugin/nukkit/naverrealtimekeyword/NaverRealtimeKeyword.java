package hancho.plugin.nukkit.naverrealtimekeyword;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.element.Element;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hancho.plugin.nukkit.mailbox.mailbox;
import hancho.plugin.nukkit.naverrealtimekeyword.entities.Keyword;
import hancho.plugin.nukkit.naverrealtimekeyword.entities.MainEntity;
import me.onebone.economyapi.EconomyAPI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public class NaverRealtimeKeyword extends PluginBase implements Listener {
    public static final String PREFIX = "§f[§c ! §f]";
    public static final int MAIN_FORM_ID = 236814;
    public static final int CHECK_FORM_ID = MAIN_FORM_ID + 1;
    public static final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd일 HH시 mm분");
    public static NaverRealtimeKeyword instance;

    public MainEntity entity;
    public LinkedHashMap<String, Keyword> keywordRankMap = new LinkedHashMap<>();
    public LinkedHashMap<String, String> selectedKeywordMap = new LinkedHashMap<>();
    public LinkedHashMap<UUID, String> playerNameMap = new LinkedHashMap<>();

    public EconomyAPI economyAPI;
    public mailbox mailBox;

    public static String getLocationHash(Location location) {
        return location.x + "," + location.y + "," + location.z + "," + location.getLevel().getName();
    }

    public static Location getLocationFromString(String str) {
        String[] strings = str.split(",");
        return new Location(
                Double.parseDouble(strings[0]),
                Double.parseDouble(strings[1]),
                Double.parseDouble(strings[2]),
                Server.getInstance().getLevelByName(strings[3]));
    }

    @Override
    public void onEnable() {
        instance = this;

        Entity.registerEntity("realtimeKeyword", MainEntity.class);
        this.saveDefaultConfig();
        this.economyAPI = (EconomyAPI) this.getServer().getPluginManager().getPlugin("EconomyAPI");
        this.mailBox = (mailbox) this.getServer().getPluginManager().getPlugin("mailbox");
        this.getServer().getScheduler().scheduleRepeatingTask(this, this::updateRanking, 20 * 60, true);
        this.getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        this.killEntity();
        this.killAllEntities();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName()) {
            case "실검위치":
                if (sender instanceof Player) {
                    this.getConfig().set("loc", getLocationHash(((Player) sender).getLocation().add(0, 2, 0)));
                    this.saveConfig();
                    this.spawnEntity();
                    sender.sendMessage("변경 및 스폰되었습니다.");
                }
                break;
            case "실검청소":
                if (this.getConfig().exists("loc")) {
                    Location location = getLocationFromString(this.getConfig().getString("loc"));
                    for (Entity entity : location.getLevel().getEntities()) {
                        if (entity instanceof MainEntity) {
                            entity.kill();
                            entity.close();
                        }
                    }
                }
                this.saveConfig();
                this.spawnEntity();
                sender.sendMessage("청소 되었습니다.");
                break;
            case "실검도박":
                this.showMainForm((Player) sender);
                break;
        }

        return true;
    }

    @EventHandler
    public void onPlayerFormResponded(PlayerFormRespondedEvent ev) {
        if (ev.getWindow() == null) return;
        if (ev.getResponse() == null) return;

        Player player = ev.getPlayer();
        int formId = ev.getFormID();

        if (ev.getWindow() instanceof FormWindowSimple) {
            FormWindowSimple window = (FormWindowSimple) ev.getWindow();
            FormResponseSimple response = window.getResponse();

            if (formId == MAIN_FORM_ID) {
                String keyword = response.getClickedButton().getText().split("§l")[1];

                if (!this.keywordRankMap.containsKey(keyword)) {
                    player.sendMessage(PREFIX + " §c해당 키워드는 더이상 사용할 수 없습니다.");
                    return;
                }

                this.selectedKeywordMap.put(player.getName(), keyword);
                this.showCheckingForm(player);
            }

        } else if (ev.getWindow() instanceof FormWindowCustom) {
            FormWindowCustom window = (FormWindowCustom) ev.getWindow();
            FormResponseCustom response = window.getResponse();

            if (formId == CHECK_FORM_ID) {
                String keywordName = this.selectedKeywordMap.get(player.getName());
                Keyword keyword = this.keywordRankMap.get(keywordName);
                int price;

                if (keyword == null) {
                    player.sendMessage(PREFIX + " §c해당 키워드는 더이상 사용할 수 없습니다.");
                    return;
                }

                if (keyword.getRanking() < 3) {
                    player.sendMessage(PREFIX + " §c해당 키워드가 3위 이하일 때만 선택할 수 있습니다.");
                    player.sendMessage(PREFIX + " §c다른 키워드로 다시 시도해주세요.");
                    return;
                }

                try {
                    price = Integer.parseInt(response.getInputResponse(1));
                } catch (NumberFormatException e) {
                    player.sendMessage(PREFIX + " §f자연수가 아니거나 잘못된 가격을 입력하셨습니다.");
                    return;
                }

                if (price < 10000 || price > 30000) {
                    player.sendMessage(PREFIX + " §f금액의 범위는 1만원 ~ 3만원 사이여야합니다.");
                    return;
                }

                if (this.economyAPI.myMoney(player) < price * 1.5) {
                    player.sendMessage(PREFIX + " §f보유 금액이 입력한 금액의 1.5배보다 작아 취소되었습니다.");
                    return;
                }

                keyword.addPlayer(player, price);
                player.sendMessage(PREFIX + " §f입력된 금액 : " + price + ", 키워드 : " + this.selectedKeywordMap.get(player.getName()));
                player.sendTitle(
                        this.selectedKeywordMap.get(player.getName()),
                        "§d순위 변동이 발생하면 결과를 알려드립니다",
                        20, 50, 20);

                this.getLogger().info(player.getName() + "님이 키워드 \"" + this.selectedKeywordMap.get(player.getName()) + "\" 키워드에 " + price + "원을 입력했습니다.");
            }
        }
    }

    public void addMail(String name, String msg) {
        if (this.mailBox == null) return;
        this.mailBox.addMail(name, msg);
    }


    public void spawnEntity() {
        String locationHash;
        Entity spawnedEntity;

        if (this.getConfig().exists("loc")) {
            this.killEntity();

            locationHash = this.getConfig().getString("loc");
            Location location = getLocationFromString(locationHash);

            spawnedEntity = Entity.createEntity("realtimeKeyword", location);
            spawnedEntity.setNameTagVisible();
            spawnedEntity.setNameTagAlwaysVisible(true);
            spawnedEntity.setScale(0.01f);
            spawnedEntity.setNameTag("§7(실검 불러오는 중)");
            spawnedEntity.spawnToAll();

            this.entity = (MainEntity) spawnedEntity;
        }
    }

    public void killEntity() {
        if (this.entity != null) {
            if (!this.entity.getChunk().isLoaded()) {
                try {
                    this.entity.chunk.load();
                } catch (IOException e) {
                    this.getLogger().error("", e);
                }
            }
            this.entity.kill();
            this.entity.close();
        }
    }

    public void killAllEntities() {
        for (Level level : this.getServer().getLevels().values()) {
            for (Entity entity : level.getEntities()) {
                if (entity instanceof MainEntity) {
                    entity.kill();
                    entity.close();
                }
            }
        }
    }

    public void updateEntityNameTag() {
        if (this.entity == null || this.keywordRankMap == null) return;
        StringBuilder entityNameBuilder = new StringBuilder();
        TreeMap<Integer, Keyword> treeMap = new TreeMap<>();

        entityNameBuilder
                .append("§o§a[ §f네이버 실시간 검색어 §a]" + "\n§7")
                .append(simpleDateFormat.format(System.currentTimeMillis())).append("\n");

        for (Keyword keyword : this.keywordRankMap.values()) {
            treeMap.put(keyword.getRanking(), keyword);
        }

        for (Map.Entry<Integer, Keyword> entry : treeMap.entrySet()) {
            Keyword keyword = entry.getValue();
            String keywordName = keyword.getName();
            entityNameBuilder.append("\n§b").append(keyword.getRanking()).append("§7위 : §f").append(keywordName);
        }

        if (!this.entity.isAlive()) {
            this.killEntity();
            this.spawnEntity();
        }

        this.entity.setNameTag(entityNameBuilder.toString());
    }

    public void updateRanking() {
        StringBuilder stringBuilder = new StringBuilder();
        URL url;
        String line;

        try {
            url = new URL("https://apis.naver.com/mobile_main/srchrank/srchrank?frm=main&ag=all&gr=1&ma=-2&si=0&en=0&sp=0");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            BufferedReader bufferReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            while ((line = bufferReader.readLine()) != null) {
                stringBuilder.append(line);
            }

        } catch (IOException e) {
            this.getLogger().error("", e);
        }

        JsonObject newData = new JsonParser().parse(stringBuilder.toString()).getAsJsonObject();
        JsonArray realtimeData = (JsonArray) newData.get("data");
        HashSet<String> updatedKeywordSet = new HashSet<>();

        for (JsonElement jsonElement : realtimeData) {
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            Keyword keyword;

            if ((keyword = this.keywordRankMap.get(jsonObject.get("keyword").getAsString())) == null) {
                keyword = new Keyword(jsonElement);
                this.keywordRankMap.put(keyword.getName(), keyword);
            } else {
                keyword.updateRank(jsonObject);
            }

            updatedKeywordSet.add(keyword.getName());
        }

        Set<String> rankMapKeySet = new HashSet<>(this.keywordRankMap.keySet());
        for (String key : rankMapKeySet) {
            if (updatedKeywordSet.contains(key)) continue;

            Keyword keyword = this.keywordRankMap.get(key);
            keyword.close();

            this.keywordRankMap.remove(key);
        }

        if (this.entity == null) spawnEntity();
        this.updateEntityNameTag();
    }

    public void showMainForm(Player player) {
        ArrayList<ElementButton> buttons = new ArrayList<>();

        for (Keyword keyword : this.keywordRankMap.values()) {
            int rank = keyword.getRanking();
            if (rank == 1 || rank == 2) {       // Ignore
                continue;
            }

            buttons.add(new ElementButton("§o" + rank + "위\n§0§l" + keyword.getName()));
        }

        FormWindowSimple form = new FormWindowSimple("§0실검 도박",
                "§f§o원하는 실시간 검색어를 선택하세요.\n "
                        + "§o실시간 검색어는 약 1분마다 동기화됩니다."
                        + "\n\n§o2위 상승했을 경우 : 2배 지급"
                        + "\n3위 하락했을 경우 : 3배 잃음"
                        + "\n§c주의! (예외적으로) 1위 하락했을 경우엔 1.5배를 잃습니다.",
                buttons);
        player.showFormWindow(form, MAIN_FORM_ID);
    }

    public void showCheckingForm(Player player) {
        for (Keyword keyword : this.keywordRankMap.values()) {
            if (keyword.getPlayerList().containsKey(player.getUniqueId())) {
                player.sendMessage(PREFIX + " §f이미 참여하셨습니다. 결과를 기다려주세요.");
                return;
            }
        }

        this.playerNameMap.put(player.getUniqueId(), player.getName());

        ArrayList<Element> formElements = new ArrayList<>();
        formElements.add(new ElementLabel("§f선택한 검색어 : §b" + this.selectedKeywordMap.get(player.getName()) + "\n§f가격은 10000 ~ 30000 까지만 가능합니다.\n§c[ ! ] 취소할 수 없습니다."));
        formElements.add(new ElementInput("가격"));

        FormWindowCustom form = new FormWindowCustom("", formElements);
        player.showFormWindow(form, CHECK_FORM_ID);

        this.getLogger().info(player.getName() + "님이 선택한 키워드 : " + this.selectedKeywordMap.get(player.getName()));
    }
}
