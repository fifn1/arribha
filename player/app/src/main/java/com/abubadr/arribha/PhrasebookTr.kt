package com.abubadr.arribha

/**
 * Hand-written Turkish for common game lines (the wording Turkish players know from localized games).
 * Keys match Phrasebook (lower case, no end punctuation). "ZZ0"… are button icons.
 */
object PhrasebookTr {
    val exact: Map<String, String> = mapOf(
        // menus and buttons
        "continue" to "Devam", "back" to "Geri", "confirm" to "Onayla", "cancel" to "İptal",
        "select" to "Seç", "ok" to "Tamam", "yes" to "Evet", "no" to "Hayır", "skip" to "Atla",
        "pause" to "Duraklat", "paused" to "Duraklatıldı", "resume" to "Devam et", "resume game" to "Oyuna devam et",
        "options" to "Seçenekler", "settings" to "Ayarlar", "game settings" to "Oyun ayarları",
        "new game" to "Yeni oyun", "load game" to "Oyun yükle", "save game" to "Oyunu kaydet",
        "continue game" to "Oyuna devam et", "quit" to "Çık", "quit game" to "Oyundan çık", "exit" to "Çıkış",
        "main menu" to "Ana menü", "main menu - demo" to "Ana menü - Demo",
        "restart" to "Yeniden başlat", "restart checkpoint" to "Kontrol noktasından başla", "retry" to "Tekrar dene",
        "try again" to "Tekrar dene", "loading" to "Yükleniyor", "saving" to "Kaydediliyor",
        "game saved" to "Oyun kaydedildi", "progress saved" to "İlerleme kaydedildi", "autosave" to "Otomatik kayıt",
        "checkpoint" to "Kontrol noktası", "checkpoint reached" to "Kontrol noktasına ulaşıldı",
        "change selection" to "Seçimi değiştir", "apply" to "Uygula", "default" to "Varsayılan", "reset" to "Sıfırla",
        "demo" to "Demo", "credits" to "Jenerik", "tutorial" to "Öğretici",
        "hint" to "İpucu", "tip" to "İpucu", "warning" to "Uyarı", "locked" to "Kilitli", "unlocked" to "Kilit açıldı",
        "coming soon" to "Yakında", "on" to "Açık", "off" to "Kapalı",
        // settings
        "difficulty" to "Zorluk", "easy" to "Kolay", "normal" to "Normal", "hard" to "Zor", "very hard" to "Çok zor",
        "audio" to "Ses", "video" to "Görüntü", "display" to "Ekran", "controls" to "Kontroller",
        "subtitles" to "Altyazılar", "language" to "Dil", "brightness" to "Parlaklık", "volume" to "Ses seviyesi",
        "music" to "Müzik", "sound effects" to "Ses efektleri", "accessibility" to "Erişilebilirlik",
        "camera" to "Kamera", "vibration" to "Titreşim", "invert y-axis" to "Y eksenini ters çevir",
        // prompts
        "press any button" to "Herhangi bir tuşa bas", "press any button to continue" to "Devam etmek için herhangi bir tuşa bas",
        "press start" to "Start tuşuna bas", "press to start" to "Başlamak için bas", "press start to begin" to "Başlamak için Start tuşuna bas",
        "interact" to "Etkileşim", "talk" to "Konuş", "open" to "Aç", "close" to "Kapat", "pick up" to "Al",
        "jump" to "Zıpla", "attack" to "Saldır", "dodge" to "Kaç", "block" to "Engelle", "sprint" to "Koş",
        "crouch" to "Eğil", "aim" to "Nişan al", "shoot" to "Ateş et", "reload" to "Şarjör değiştir",
        "climb" to "Tırman", "swim" to "Yüz", "use" to "Kullan", "equip" to "Kuşan", "unequip" to "Çıkar",
        "drop" to "Bırak", "discard" to "Çöpe at", "buy" to "Satın al", "sell" to "Sat", "upgrade" to "Yükselt",
        "do not turn off the system while this icon is displayed" to "Bu simge görünürken sistemi kapatma",
        // game events
        "game over" to "Oyun bitti", "you died" to "Öldün", "victory" to "Zafer", "defeat" to "Yenilgi",
        "you win" to "Kazandın", "you lose" to "Kaybettin",
        "mission complete" to "Görev tamamlandı", "mission failed" to "Görev başarısız",
        "quest complete" to "Görev tamamlandı", "quest completed" to "Görev tamamlandı",
        "new quest" to "Yeni görev", "new quest received" to "Yeni görev", "quest updated" to "Görev güncellendi",
        "new objective" to "Yeni hedef", "objective updated" to "Hedef güncellendi", "objective complete" to "Hedef tamamlandı",
        "level up" to "Seviye atladın", "level complete" to "Bölüm tamamlandı", "chapter complete" to "Bölüm tamamlandı",
        "achievement unlocked" to "Başarım kazanıldı", "trophy unlocked" to "Kupa kazanıldı",
        "new item" to "Yeni eşya", "item acquired" to "Eşya alındı", "secret found" to "Sır bulundu",
        "inventory" to "Envanter", "inventory full" to "Envanter dolu", "map" to "Harita", "journal" to "Günlük",
        "quests" to "Görevler", "skills" to "Yetenekler", "equipment" to "Ekipman", "upgrades" to "Yükseltmeler",
        "shop" to "Mağaza", "health" to "Sağlık", "ammo" to "Cephane", "collectibles" to "Koleksiyon öğeleri",
        "enemy" to "Düşman", "boss" to "Boss",
        // everyday lines
        "are you sure" to "Emin misin?", "are you sure you want to quit" to "Çıkmak istediğine emin misin?",
        "unsaved progress will be lost" to "Kaydedilmemiş ilerleme kaybolacak", "do you want to save" to "Kaydetmek istiyor musun?",
        "follow me" to "Beni takip et", "over here" to "Buradayım", "look out" to "Dikkat et", "watch out" to "Dikkat!",
        "let's go" to "Hadi gidelim", "hurry" to "Acele et", "hurry up" to "Çabuk ol", "come on" to "Hadi",
        "thank you" to "Teşekkür ederim", "thanks" to "Teşekkürler", "good job" to "Aferin", "well done" to "Harika iş",
        "be careful" to "Dikkatli ol", "who are you" to "Sen kimsin?", "where am i" to "Neredeyim?",
        "what happened" to "Ne oldu?", "i don't know" to "Bilmiyorum", "no way" to "Olamaz",
        "missed me" to "Iskaladın!", "help me" to "Yardım et", "wait" to "Bekle", "stop" to "Dur",
        "what was that" to "O neydi?", "we're safe here" to "Burada güvendeyiz", "i'm sorry" to "Özür dilerim",
        // open-world / crime games (GTA style)
        "wasted" to "Öldün", "busted" to "Tutuklandın", "mission passed" to "Görev başarılı",
        "lose the cops" to "Polislerden kurtul", "lose the police" to "Polislerden kurtul", "evade the police" to "Polisten kaç",
        "lose your wanted level" to "Aranma seviyeni düşür", "wanted level" to "Aranma seviyesi",
        "get in the car" to "Arabaya bin", "get in a car" to "Bir arabaya bin", "get in the vehicle" to "Araca bin",
        "get back in the car" to "Arabaya geri bin", "get back in the vehicle" to "Araca geri bin",
        "return to the vehicle" to "Araca geri dön", "get out of the car" to "Arabadan in",
        "follow the target" to "Hedefi takip et", "lose the target" to "Takipten kurtul", "the target is getting away" to "Hedef kaçıyor",
        "you lost the target" to "Hedefi kaybettin", "you abandoned the mission" to "Görevi bıraktın",
        "destination" to "Hedef nokta", "waypoint set" to "Yol noktası belirlendi", "new message" to "Yeni mesaj",
        "new contact" to "Yeni kişi", "incoming call" to "Gelen arama", "answer" to "Cevapla", "hang up" to "Kapat",
        "phone" to "Telefon", "money" to "Para", "cash" to "Nakit", "weapon" to "Silah", "weapons" to "Silahlar",
        "vehicle" to "Araç", "garage" to "Garaj", "safehouse" to "Güvenli ev", "hospital" to "Hastane",
        "police station" to "Karakol", "store" to "Mağaza", "bank" to "Banka", "airport" to "Havalimanı",
        "take cover" to "Siper al", "stay in cover" to "Siperde kal", "stay out of sight" to "Görünmeden ilerle",
        "you've been spotted" to "Fark edildin", "get to cover" to "Sipere geç",
    )

    /** "to jump" -> "Zıplamak": Turkish infinitive used as "Zıplamak için ZZ0 tuşuna bas" */
    val toVerb: Map<String, String> = mapOf(
        "continue" to "Devam etmek", "jump" to "Zıplamak", "attack" to "Saldırmak", "dodge" to "Kaçmak", "skip" to "Atlamak",
        "interact" to "Etkileşime geçmek", "talk" to "Konuşmak", "open" to "Açmak", "pick up" to "Almak", "climb" to "Tırmanmak",
        "sprint" to "Koşmak", "crouch" to "Eğilmek", "aim" to "Nişan almak", "shoot" to "Ateş etmek",
        "reload" to "Şarjör değiştirmek", "start" to "Başlamak", "begin" to "Başlamak", "confirm" to "Onaylamak",
        "cancel" to "İptal etmek", "go back" to "Geri dönmek", "select" to "Seçmek", "use" to "Kullanmak",
        "paint" to "Boyamak", "spin" to "Dönmek", "spin attack" to "Dönerek saldırmak",
    )
}
