package hancho.plugin.nukkit.naverRealtimeKeyword;

import cn.nukkit.Player;
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
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.Sound;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hancho.plugin.nukkit.mailbox.mailbox;
import me.onebone.economyapi.EconomyAPI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public class NaverRealtimeKeyword extends PluginBase implements Listener {
    public static final int MAIN_FORM = 236814;
    public static final int CHECK_FORM = MAIN_FORM +1;
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd일 HH시 mm분");
    public JsonArray realtimeData;
    public LinkedHashMap<String, Integer> rank;
    public Entity entity;

    public LinkedHashMap<String, ArrayList<UUID>> stocks = new LinkedHashMap<>();
    public LinkedHashMap<UUID, Integer> inputPrice = new LinkedHashMap<>();
    public LinkedHashMap<String, String> selectedKeyWord = new LinkedHashMap<>();

    public LinkedHashMap<UUID, String> uuidToName = new LinkedHashMap<>();

    public boolean isFirst = true;
    public EconomyAPI economyAPI;
    public mailbox mailBox;

    public String getKeyword(JsonElement element){
        JsonObject object = element.getAsJsonObject();
        return object.get("keyword").getAsString();
    }

    public static String locToString(Location location){
        return location.x + "," + location.y + "," + location.z + "," + location.getLevel().getName();
    }

    public Location stringToLoc(String str){
        String[] strings = str.split(",");
        Location location = new Location(Double.parseDouble(strings[0])
                ,Double.parseDouble(strings[1])
                ,Double.parseDouble(strings[2])
                ,this.getServer().getLevelByName(strings[3]));
        return location;
    }

    public void addMail(String name, String msg){
        if(this.mailBox == null) return;
        this.mailBox.addMail(name, msg);
    }

    @Override
    public void onEnable() {
        Entity.registerEntity("realtimeKeyword", MainEntity.class);
        this.saveDefaultConfig();
        this.economyAPI = (EconomyAPI) this.getServer().getPluginManager().getPlugin("EconomyAPI");
        this.mailBox = (mailbox) this.getServer().getPluginManager().getPlugin("mailbox");
        this.getServer().getScheduler().scheduleRepeatingTask(this, this::check, 20 * 60, true);
        this.getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        this.killEntity();
        this.killAll();
    }

    public void killEntity(){
        if(this.entity != null){
            if(!this.entity.getChunk().isLoaded()){
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

    public void killAll(){
        for (Level level : this.getServer().getLevels().values()) {
            for (Entity entity : level.getEntities()) {
                if (entity instanceof MainEntity) {
                    entity.kill();
                    entity.close();
                }
            }
        }
    }

    public void spawn(){
        String locKey;
        Entity spawnedEntity;
        if(this.getConfig().exists("loc")){
            locKey = this.getConfig().getString("loc");
            this.killEntity();
            Location location = this.stringToLoc(locKey);
            spawnedEntity = Entity.createEntity("realtimeKeyword", location );
            spawnedEntity.setNameTagVisible();
            spawnedEntity.setNameTagAlwaysVisible(true);
            spawnedEntity.setScale(0.01f);
            spawnedEntity.setNameTag("§7(실검 불러오는 중)");
            spawnedEntity.spawnToAll();
            this.entity = spawnedEntity;
        }
    }

    public void generateEntityName(){
        if(this.entity == null || this.realtimeData == null) return;

        HashSet<String> nowRank = new HashSet<>();
        String name = "§o§a[ §f네이버 실시간 검색어 §a]" +
                "\n§7" + DATE_FORMAT.format(System.currentTimeMillis()) + "\n";
        int rank = 1;

        for(JsonElement element : this.realtimeData){
            String keyword = this.getKeyword(element);
            name += "\n§b" + rank + "§7위 : §f" + keyword;
            if(this.rank == null) {
                this.rank = new LinkedHashMap<>();
            }
            if(!this.isFirst){
                int oldRank;
                if (!this.rank.containsKey(keyword)) {
                    name += " §eNew";
                }else if((oldRank = this.rank.get(keyword)) != rank){
                    name += " " + (oldRank > rank ? "§b↑§f" : "§c↓§f") + Math.abs(rank - oldRank);
                    ArrayList<UUID> list = this.stocks.get(keyword);
                    if(list != null){
                        int changedValueAbs = Math.abs(rank - oldRank);
                        if(oldRank > rank){ //승
                            double changedValue = changedValueAbs;
                            for(UUID uuid : list) {
                                Optional<Player> player = this.getServer().getPlayer(uuid);
                                if (this.economyAPI.myMoney(uuid) < this.inputPrice.get(uuid)){
                                    player.ifPresent(value -> value.sendMessage("§c부정행위 감지로 실검도박이 취소되었습니다."));
                                }
                                this.economyAPI.addMoney(uuid, this.inputPrice.get(uuid) * changedValue);
                                player.ifPresent(value -> {
                                    value.sendTitle("§b승리!", "키워드 :§d " + keyword + "§f에서 §d" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 얻으셨습니다", 20, 60, 20 );
                                    value.sendMessage("§l§b[ §f실검도박 §b] §f 키워드 :§d " + keyword + "§f에서 §d" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 얻으셨습니다!");
                                    value.level.addSound(value, Sound.RANDOM_LEVELUP, 1, 1, value);
                                });
                                if(!player.isPresent()){
                                    this.addMail(this.uuidToName.get(uuid), "§l§b[ §f실검도박 §b] §f 키워드 :§d " + keyword + "§f에서 §d" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 얻으셨습니다!");
                                }
                                this.inputPrice.remove(uuid);
                            }
                            this.getServer().broadcastMessage("§o§b[ §f실검도박 §b] §f총 §b" + list.size() + "§f명이 키워드 \"" + keyword + "\" (으)로 도박에 §b" + changedValue + "§f배로 성공하셨습니다.");
                        }else{ //패
                            double changedValue = changedValueAbs == 1 ? 1.5 : changedValueAbs;
                            for(UUID uuid : list) {
                                Optional<Player> player = this.getServer().getPlayer(uuid);
                                if (this.economyAPI.myMoney(uuid) < this.inputPrice.get(uuid)){
                                    this.getServer().getLogger().warning(uuid.toString() + "님이 돈 " + this.economyAPI.myMoney(uuid) + "원으로 돈이 부족합니다. 배팅 금액 : " + this.inputPrice.get(uuid));
                                }
                                this.economyAPI.reduceMoney(uuid, this.inputPrice.get(uuid) * changedValue);
                                player.ifPresent(value -> {
                                    value.sendTitle("§c손실", "키워드 :§d " + keyword + "§f에서 §d-" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 잃셨습니다", 20, 60, 20 );
                                    value.sendMessage("§l§c[ §f실검도박 §c] §f 키워드 :§d " + keyword + "§f에서 §d-" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 잃으셨습니다.");
                                    value.level.addSound(value, Sound.NOTE_BANJO, 1, 1, value);
                                });
                                if(!player.isPresent()){
                                    this.addMail(this.uuidToName.get(uuid), "§l§c[ §f실검도박 §c] §f 키워드 :§d " + keyword + "§f에서 §d-" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 잃으셨습니다.");
                                }
                                this.inputPrice.remove(uuid);
                            }
                        }
                        this.stocks.remove(keyword);
                    }
                }
            }
            this.rank.put(keyword, rank);
            nowRank.add(keyword);
            rank++;
        }
        HashSet<String> keySet = new HashSet<>(this.rank.keySet());
        for(String keyword : keySet){
            if(!nowRank.contains(keyword)) {
                ArrayList<UUID> list = this.stocks.get(keyword);
                if (list != null) {  // :\
                    int changedValueAbs = Math.abs(21 - this.rank.get(keyword));
                    double changedValue = changedValueAbs == 1 ? 1.5 : changedValueAbs;
                    for (UUID uuid : list) {
                        Optional<Player> player = this.getServer().getPlayer(uuid);
                        if (this.economyAPI.myMoney(uuid) < this.inputPrice.get(uuid)) {
                            this.getServer().getLogger().warning(uuid.toString() + "님이 돈 " + this.economyAPI.myMoney(uuid) + "원으로 돈이 부족합니다. 배팅 금액 : " + this.inputPrice.get(uuid));
                        }
                        this.economyAPI.reduceMoney(uuid, this.inputPrice.get(uuid) * changedValue);
                        player.ifPresent(value -> {
                            value.sendTitle("§c손실", "키워드 :§d " + keyword + "§f에서 §d-" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 잃셨습니다", 20, 60, 20);
                            value.sendMessage("§l§c[ §f실검도박 §c] §f 키워드 :§d " + keyword + "§f에서 §d-" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 잃으셨습니다.");
                            value.level.addSound(value, Sound.NOTE_BANJO, 1, 1, value);
                        });
                        if(!player.isPresent()){
                            this.addMail(this.uuidToName.get(uuid), "§l§c[ §f실검도박 §c] §f 키워드 :§d " + keyword + "§f에서 §d-" + changedValue + "§f배로 총 §d" + (this.inputPrice.get(uuid) * changedValue) + "§f원 잃으셨습니다.");
                        }
                        this.inputPrice.remove(uuid);
                    }
                }
                this.rank.remove(keyword);
                this.stocks.remove(keyword);
            }
        }
        if(this.isFirst){
            this.isFirst = false;
        }
        if(!this.entity.isAlive()){
            this.killEntity();
            this.spawn();
        }
        this.entity.setNameTag(name);
    }

    public void check(){
        StringBuilder sb = new StringBuilder();
        URL url;
        String line;
        try {
            url = new URL("https://apis.naver.com/mobile_main/srchrank/srchrank?frm=main&ag=all&gr=1&ma=-2&si=0&en=0&sp=0");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            while ((line = br.readLine()) != null){
                sb.append(line);
            }
        } catch (IOException e) {
            this.getLogger().error("", e);
        }
        JsonObject newData = new JsonParser().parse(sb.toString()).getAsJsonObject();
        JsonArray realtimeData = (JsonArray) newData.get("data");
        String nowTop = this.getKeyword(realtimeData.get(0));
        if(this.realtimeData != null) {
            if (!this.getKeyword(this.realtimeData.get(0)).equals(nowTop)) {
                this.getServer().broadcastMessage("§o§a[ §f네이버 실시간 검색어 §a] §71위 변동 : §f" + nowTop);
            }
        }
        this.realtimeData = realtimeData;
        if(this.isFirst) spawn();
        this.generateEntityName();
        //this.getLogger().info(this.getKeyword(realtimeData.get(0)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(command.getName().equals("실검위치")) {
            if (sender instanceof Player) {
                this.getConfig().set("loc", locToString(((Player) sender).getLocation().add(0, 2, 0)));
                this.saveConfig();
                this.spawn();
                sender.sendMessage("변경 및 스폰되었습니다.");
            }
        }else if(command.getName().equals("실검청소")){
            if(this.getConfig().exists("loc")){
                Location location = this.stringToLoc(this.getConfig().getString("loc"));
                for(Entity entity : location.getLevel().getEntities()){
                    if(entity instanceof MainEntity){
                        entity.kill();
                        entity.close();
                    }
                }
            }
            this.saveConfig();
            this.spawn();
            sender.sendMessage("청소 되었습니다.");
        }else if(command.getName().equals("실검도박")){
            this.showMainForm((Player) sender);
        }
        return true;
    }

    @EventHandler
    public void onRespond(PlayerFormRespondedEvent ev) {
        if (ev.getWindow() == null) return;
        if (ev.getResponse() == null) return;
        Player player = ev.getPlayer();
        String name = player.getName();
        int id = ev.getFormID();
        if (ev.getWindow() instanceof FormWindowSimple) {
            FormWindowSimple window = (FormWindowSimple) ev.getWindow();
            FormResponseSimple response = window.getResponse();
            if(id == MAIN_FORM){
                String keyword = response.getClickedButton().getText().split("§l")[1];
                if(!this.rank.containsKey(keyword)){
                    player.sendMessage("§f[§c ! §f] §c해당 키워드는 더이상 사용할 수 없습니다.");
                    return;
                }
                this.selectedKeyWord.put(player.getName(), keyword);
                this.showCheckForm(player);
            }
        } else if (ev.getWindow() instanceof FormWindowCustom) {
            FormWindowCustom window = (FormWindowCustom) ev.getWindow();
            FormResponseCustom response = window.getResponse();
            if(id == CHECK_FORM){
                String keyword = this.selectedKeyWord.get(player.getName());
                if(!this.rank.containsKey(keyword)){
                    player.sendMessage("§f[§c ! §f] §c해당 키워드는 더이상 사용할 수 없습니다.");
                    return;
                }
                if(this.rank.get(keyword) < 3){
                    player.sendMessage("§f[§c ! §f] §c해당 키워드가 3위 이하일 때만 선택할 수 있습니다.");
                    player.sendMessage("§f[§c ! §f] §c다른 키워드로 다시 시도해주세요.");
                    return;
                }
                int price;
                try{
                    price = Integer.parseInt(response.getInputResponse(1));
                }catch (NumberFormatException e){
                    player.sendMessage("§f[§c ! §f] §f자연수가 아니거나 잘못된 가격을 입력하셨습니다.");
                    return;
                }
                if(price < 50000 || price > 500000){
                    player.sendMessage("§f[§c ! §f] §f금액의 범위는 5만원 ~ 50만원 사이여야합니다.");
                    return;
                }
                if(this.economyAPI.myMoney(player) < price * 1.5){
                    player.sendMessage("§f[§c ! §f] §f보유 금액이 입력한 금액의 1.5배보다 작아 취소되었습니다.");
                    return;
                }
                this.getLogger().info(player.getName() + "님이 키워드 \"" + this.selectedKeyWord.get(player.getName()) + "\" 키워드에 " + price + "원을 입력했습니다.");
                this.inputPrice.put(player.getUniqueId(), price);
                ArrayList<UUID> list = this.stocks.getOrDefault(keyword, new ArrayList<>());
                list.add(player.getUniqueId());
                this.stocks.put(keyword, list);
                player.sendMessage("§f[§c ! §f] §f입력된 금액 : " + price + ", 키워드 : " + this.selectedKeyWord.get(player.getName()));
                player.sendTitle(this.selectedKeyWord.get(player.getName()), "§d순위 변동이 발생하면 결과를 알려드립니다", 20, 50, 20);
            }
        } else if (ev.getWindow() instanceof FormWindowModal) {
            FormWindowModal window = (FormWindowModal) ev.getWindow();
            FormResponseModal response = window.getResponse();

        }
    }

    public void showMainForm(Player player){
        ArrayList<ElementButton> buttons = new ArrayList<>();
        int rank = 1;
        for(JsonElement element : this.realtimeData) {
            if(rank == 1 || rank == 2){
                rank ++;
                continue;
            }
            String keyword = this.getKeyword(element);
            buttons.add(new ElementButton("§o" + rank + "위\n§0§l" + keyword));
            rank++;
        }
        FormWindowSimple form = new FormWindowSimple("§0실검 도박","§f§o원하는 실시간 검색어를 선택하세요.\n " +
                "§o실시간 검색어는 약 1분마다 동기화됩니다." +
                "\n\n§o2위 상승했을 경우 : 2배 지급" +
                "\n3위 하락했을 경우 : 3배 잃음" +
                "\n§c주의! (예외적으로) 1위 하락했을 경우엔 1.5배를 잃습니다." ,buttons);
        player.showFormWindow(form, MAIN_FORM);
    }

    public void showCheckForm(Player player){
        if(this.inputPrice.containsKey(player.getUniqueId())){
            player.sendMessage("§f[§c ! §f] §f이미 참여하셨습니다. 결과를 기다려주세요.");
            return;
        }
        this.uuidToName.put(player.getUniqueId(), player.getName());
        ArrayList<Element> elements = new ArrayList<>();
        elements.add(new ElementLabel("§f선택한 검색어 : §b" + this.selectedKeyWord.get(player.getName()) + "\n§f가격은 50000 ~ 500000 까지만 가능합니다.\n§c[ ! ] 취소할 수 없습니다."));
        elements.add(new ElementInput("가격"));
        FormWindowCustom form = new FormWindowCustom("", elements);
        player.showFormWindow(form, CHECK_FORM);
        this.getLogger().info(player.getName() + "님이 선택한 키워드 : " + this.selectedKeyWord.get(player.getName()));
    }
}
