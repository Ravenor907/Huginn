pragma Singleton
import QtQuick
import Huginn

// Adding people you trust (cap i1), files over the internet (cap f1) and linked devices, the same as
// Muninn's. Shared by the chat, group, contacts and settings pages so each only calls in here.
QtObject {
    // ------------------------------------------------------------- i1: checking and introducing

    // The 4 pictures (and the number older apps show) for a contact; "It matches" marks them checked.
    function checkCode(k) {
        const n = Chat.nameOf(k), e = Trust.emojiWith(k)
        Ui.sure("Check it's really " + n, (e !== "" ? e + "\n\n" : "") + "Code: " + Chat.codeWith(k)
                + "\n\nAsk them to open your chat and choose Check code. If their screen shows the same 4 pictures (or the same number), nobody is in between.",
                "It matches", () => Chat.verify(k))
    }
    // Introduce a contact to another one: both say yes, then they're in touch.
    function introduce(k) {
        const n = Chat.nameOf(k)
        const items = Chat.chats.filter(c => c.contact && c.key !== k).map(c => ({ name: c.name, url: c.key,
            detail: Trust.hasI1(c.key) ? "" : "Needs a newer app" }))
        if (!items.length) { Ui.say("Add another contact first.", true); return }
        Ui.pickFrom("Introduce " + n + " to", [{ header: "They both get asked, and become contacts if both say yes", items: items }], other => {
            const r = Trust.introduce(k, other)
            if (r === "") Ui.say("Introduced " + n + " and " + Chat.nameOf(other) + ".")
            else Ui.say(r, true)
        })
    }
    // Rows for the Direct messages list: introductions, group members asking, Family offers.
    function pendingEntries() {
        return Trust.pending.map(p => ({ kind: "intro", id: p.id, key: p.key, name: p.name, pendingKind: p.kind, detail: p.detail }))
    }
    function answer(e) {
        const title = e.pendingKind === "intro" ? "Meet " + e.name + "?" : e.pendingKind === "offer" ? "Add " + e.name + "?" : e.name + " wants to be in touch"
        const why = e.pendingKind === "intro" ? e.detail + ". If you both say yes, you become contacts and can message from anywhere."
                  : e.pendingKind === "offer" ? e.detail.replace(": add as a contact?", "") + ". They're asked too; once both say yes you're contacts."
                  : e.detail + ". Say yes to message each other directly."
        Ui.sure(title, why, e.pendingKind === "offer" ? "Ask them" : "Yes", () => { Trust.answer(e.id, true); Ui.say(e.pendingKind === "offer" ? "Asked " + e.name + "." : "Said yes to " + e.name + ".") },
                false, false, () => Trust.answer(e.id, false))
        Ui.confirm.cancelText = e.pendingKind === "offer" ? "Not now" : "No"
    }
    // Someone in a group who isn't a contact yet (GroupPage's member options).
    function askToAdd(g, m) {
        const r = Trust.askToAdd(g, m.key)
        if (r === "") Ui.say("Asked " + m.name + ". You're contacts once they say yes too.")
        else Ui.say(r, true)
    }
    // A link that works for one person: copied, for any app or email.
    function inviteLink() {
        const link = Trust.inviteLink(24)
        if (link === "") { Ui.say("Unlock Huginn first.", true); return }
        Chat.copyText(link)
        Ui.say("One-time invite link copied. The first person to use it within a day is added straight away.")
    }
    // After adding a code: a one-time invite says hi at once, so they know it worked.
    function added(code, key) {
        if (Trust.isOnceCode(code)) Chat.sendPrivate(key, "Hi, it's " + Chat.username + ". I added you with your invite link.")
    }

    // ------------------------------------------------------------- f1: files over the internet

    function sendFile(k) {
        if (Ui.main && Ui.main.touring) return
        System.pickFiles().forEach(p => sendUrl(k, p))
    }
    function sendUrl(k, url) {
        const path = Chat.localPath(url)
        const r = FileLink.send(k, path === "" ? String(url) : path)
        if (r !== "") Ui.say(r, true)
    }
    // A dropped file that isn't a picture: straight to them when possible, otherwise on the same Wi-Fi.
    function dropFiles(k, urls) {
        if (Chat.route(k) !== "wifi" && FileLink.hasF1(k)) { urls.forEach(u => sendUrl(k, u)); return true }
        return false
    }
    // A file that came through the relays.
    function saveFile(k, m) {
        const r = FileLink.saveReceived(k, m.id)
        Ui.say(r === "" ? m.file + " is in Downloads." : r, r !== "")
    }
    function fileText(t) {
        const pct = t.size > 0 ? Math.floor(100 * t.done / t.size) : 0
        return t.state === "done" ? (t.mine ? "Sent" : t.note || "Saved")
             : t.state === "failed" ? t.note
             : t.state === "sending" ? (t.note !== "" ? t.note : (t.mine ? "Sending " : "Getting ") + pct + "% of " + System.sizeText(t.size))
             : t.note
    }

    // ------------------------------------------------------------- linked devices (section 15)

    function linkedDevices() {
        if (!Chat.ready) { Ui.say("Unlock Huginn first.", true); return }
        const items = []
        const ph = Linked.phone
        if (ph.key) items.push({ name: "Linked to " + ph.name, detail: "Its chats show in Direct messages while it's online. Choose to unlink", url: "ph" })
        else items.push({ name: "Use as a companion", detail: "Show a code for your phone: Settings › Linked devices there. Its chats then show here", url: "code" })
        items.push({ name: "Link a device", detail: "Paste the code another device shows, and it mirrors this computer's chats", url: "link" })
        Linked.companions.forEach(c => items.push({ name: "Unlink " + c.name, detail: "It mirrors this computer's chats", url: "u:" + c.key }))
        // Cap x1: everything moves here instead, and the old device is locked out.
        const move = [{ name: "Move here", detail: "You, your contacts, groups and chats come to this computer over the Wi-Fi. The old device is locked out", url: "movehere" },
                      { name: "Move from this computer", detail: "Paste the code the new device shows (it starts with MUNINNM1)", url: "moveaway" }]
        Ui.pickFrom("Linked devices", [{ header: "Use on both: contacts see nothing new, messages still come from and go to the phone", items: items },
                                       { header: "Move to another device", items: move }], a => {
            if (a === "movehere") {
                const code = Mover.showCode()
                if (code === "") { Ui.say("Connect to your home network first: moving only works there.", true); return }
                Chat.copyText(code)
                Ui.sure("Move code copied", "On your old device: Settings › Linked devices › Move from this device, and paste it. Keep both on the same Wi-Fi. Both screens then show the same 4 emoji.\n\n" + code, "Done", () => {}, false, false)
            } else if (a === "moveaway") {
                Ui.ask("Move from this computer", "Paste the code the new device shows (it starts with MUNINNM1). Only continue if the other device is yours and in front of you.", "", "Next", t => Mover.take(t))
            } else if (a === "code") {
                const code = Linked.newCode()
                Chat.copyText(code)
                Ui.sure("Link code copied", "Paste it on your phone in Settings › Linked devices within 10 minutes. It works once.\n\n" + code, "Done", () => {}, false, false)
            } else if (a === "link") {
                Ui.ask("Link a device", "Paste the code it shows (it starts with MUNINNL1)", "", "Link", t => {
                    const r = Linked.link(t)
                    Ui.say(r === "" ? "Now check the 4 emoji on both screens." : r, r !== "")
                })
            } else if (a === "ph") {
                Ui.sure("Unlink " + ph.name + "?", "Its chats leave this computer. Link again any time with a new code.", "Unlink", () => Linked.unlink(ph.key), false, true)
            } else if (a.indexOf("u:") === 0) {
                Linked.unlink(a.substring(2))
                Ui.say("Unlinked.")
            }
        })
    }
}
