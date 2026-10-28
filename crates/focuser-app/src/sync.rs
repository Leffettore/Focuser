//! Pulling the block lists from a personal server.
//!
//! The server holds one [`ConfigDocument`](crate::execute) (what
//! `ExportConfiguration` writes). Each device fetches it and merges it by list
//! id. One-way on purpose: the server wins, so there is nothing to reconcile.
//! Applications are the exception: each device keeps its own, since an app on
//! one machine is not an app on another.

use std::hash::{DefaultHasher, Hash, Hasher};
use std::time::Duration;

use focuser_core::BlockEngine;

use crate::error::{CommandError, CommandOutcome};
use crate::execute::{CONFIG_FORMAT_VERSION, ConfigDocument};

pub const SETTING_URL: &str = "sync_url";
pub const SETTING_TOKEN: &str = "sync_token";
/// Hash of the last document applied in full, so an unchanged file is a no-op.
const SETTING_LAST_HASH: &str = "sync_last_hash";

/// GET the document. Runs without the engine lock: the server may be slow or
/// down, and the blocking loop must not wait for it.
pub fn fetch(url: &str, token: Option<&str>) -> Result<String, String> {
    let mut request = ureq::get(url).timeout(Duration::from_secs(10));
    if let Some(token) = token.filter(|t| !t.is_empty()) {
        request = request.set("Authorization", &format!("Bearer {token}"));
    }
    let response = request.call().map_err(|e| e.to_string())?;
    response.into_string().map_err(|e| e.to_string())
}

pub(crate) fn merge(engine: &mut BlockEngine, json: &str) -> CommandOutcome<String> {
    let mut hasher = DefaultHasher::new();
    json.hash(&mut hasher);
    let hash = hasher.finish().to_string();
    if engine.db().get_setting(SETTING_LAST_HASH)?.as_deref() == Some(hash.as_str()) {
        return Ok("unchanged".into());
    }

    let document: ConfigDocument = serde_json::from_str(json)
        .map_err(|e| CommandError::Validation(format!("not a Focuser configuration file: {e}")))?;
    if document.version > CONFIG_FORMAT_VERSION {
        return Err(CommandError::Validation(format!(
            "file was written by a newer version of Focuser (format {} vs {CONFIG_FORMAT_VERSION})",
            document.version
        )));
    }
    // Validate everything first so a bad list can't leave a half-applied merge.
    for list in &document.block_lists {
        if let Some(config) = &list.shared_allowance {
            config.validate().map_err(CommandError::Validation)?;
        }
    }

    // Match against what is stored, not a cache from before the last merge.
    engine.refresh()?;
    let (mut added, mut updated, mut deferred) = (0, 0, 0);
    for incoming in document.block_lists {
        let existing = engine.block_lists().iter().find(|l| l.id == incoming.id);
        let Some(existing) = existing else {
            let mut list = incoming;
            list.schedule_unlocked_until = None;
            list.applications.clear();
            engine.db().create_block_list(&list)?;
            added += 1;
            continue;
        };
        // Overwriting a list mid-lock could end the lock early; retry next poll.
        if engine.is_block_list_protected(existing.id) {
            deferred += 1;
            continue;
        }
        // Device-local state the server knows nothing about.
        let mut list = incoming;
        list.lock = existing.lock.clone();
        list.protection = existing.protection.clone();
        list.schedule_unlocked_until = existing.schedule_unlocked_until;
        list.created_at = existing.created_at;
        list.applications = existing.applications.clone();
        engine.db().update_block_list(&list)?;
        updated += 1;
    }

    // Only a complete merge counts as applied, so deferred lists are retried.
    if deferred == 0 {
        engine.db().set_setting(SETTING_LAST_HASH, &hash)?;
    }
    Ok(format!(
        "added {added}, updated {updated}, deferred {deferred}"
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use focuser_common::types::{BlockList, WebsiteRule};
    use focuser_core::Database;

    fn engine() -> BlockEngine {
        BlockEngine::new(Database::open_in_memory().unwrap()).unwrap()
    }

    fn doc(lists: &[BlockList]) -> String {
        serde_json::json!({
            "version": CONFIG_FORMAT_VERSION, "app": "Focuser",
            "exported_at": "2026-10-28T00:00:00Z", "block_lists": lists,
        })
        .to_string()
    }

    #[test]
    fn adds_then_updates_in_place_then_skips_unchanged() {
        let mut engine = engine();
        let mut list = BlockList::new("Distractions");
        let first = doc(std::slice::from_ref(&list));
        assert_eq!(
            merge(&mut engine, &first).unwrap(),
            "added 1, updated 0, deferred 0"
        );
        assert_eq!(merge(&mut engine, &first).unwrap(), "unchanged");

        list.websites.push(WebsiteRule::domain("x.com"));
        let second = doc(std::slice::from_ref(&list));
        assert_eq!(
            merge(&mut engine, &second).unwrap(),
            "added 0, updated 1, deferred 0"
        );
        engine.refresh().unwrap();
        assert_eq!(engine.block_lists().len(), 1);
        assert_eq!(engine.block_lists()[0].websites.len(), 1);
    }

    #[test]
    fn keeps_each_devices_own_applications() {
        use focuser_common::types::AppRule;
        let mut engine = engine();
        let mut list = BlockList::new("Distractions");
        list.applications
            .push(AppRule::executable("server-only.exe"));
        merge(&mut engine, &doc(std::slice::from_ref(&list))).unwrap();
        engine.refresh().unwrap();
        assert!(engine.block_lists()[0].applications.is_empty());

        let mut local = engine.block_lists()[0].clone();
        local.applications.push(AppRule::executable("steam.exe"));
        engine.db().update_block_list(&local).unwrap();
        list.name = "Renamed".into();
        merge(&mut engine, &doc(&[list])).unwrap();
        engine.refresh().unwrap();
        assert_eq!(engine.block_lists()[0].name, "Renamed");
        assert_eq!(engine.block_lists()[0].applications.len(), 1);
    }

    #[test]
    fn never_deletes_local_lists() {
        let mut engine = engine();
        engine
            .db()
            .create_block_list(&BlockList::new("Local only"))
            .unwrap();
        merge(&mut engine, &doc(&[])).unwrap();
        engine.refresh().unwrap();
        assert_eq!(engine.block_lists().len(), 1);
    }

    #[test]
    fn rejects_garbage_and_newer_formats() {
        let mut engine = engine();
        assert!(merge(&mut engine, "nope").is_err());
        let newer = doc(&[]).replace(
            &format!("\"version\":{CONFIG_FORMAT_VERSION}"),
            "\"version\":999",
        );
        assert!(merge(&mut engine, &newer).is_err());
    }
}
