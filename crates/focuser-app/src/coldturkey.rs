//! Cold Turkey `.ctbbl` export → Focuser configuration document.
//!
//! Pure conversion: nothing here touches the database. The output is the same
//! document `ExportConfiguration` writes, so it can be imported locally or
//! hosted for sync. Ids are derived from names, so converting twice yields the
//! same ids and a sync can update lists in place.

use std::collections::BTreeMap;

use chrono::{NaiveTime, Utc, Weekday};
use focuser_common::types::{
    AppRule, BlockList, ExceptionRule, ExceptionType, Schedule, ScheduledProtection, TimeSlot,
    WebsiteRule,
};
use serde::Deserialize;
use uuid::Uuid;

use crate::error::CommandError;
use crate::execute::{CONFIG_FORMAT_VERSION, ConfigDocument};

/// Namespace for ids derived from Cold Turkey names (random, fixed forever).
const NAMESPACE: Uuid = Uuid::from_u128(0x6f0c_5b1e_2d4a_4c7e_9a31_5e8b_c0de_7a11);

/// Only the fields that carry meaning; Cold Turkey writes everything as strings.
#[derive(Deserialize)]
struct CtBlock {
    enabled: String,
    #[serde(rename = "type")]
    kind: String,
    lock: String,
    web: Vec<String>,
    exceptions: Vec<String>,
    apps: Vec<String>,
    schedule: Vec<CtSlot>,
}

#[derive(Deserialize)]
struct CtSlot {
    #[serde(rename = "startTime")]
    start: String,
    #[serde(rename = "endTime")]
    end: String,
}

fn id(parts: &[&str]) -> Uuid {
    Uuid::new_v5(&NAMESPACE, parts.join("\u{1f}").as_bytes())
}

/// `"d,h,m"` with Cold Turkey's day 0 = Sunday.
fn moment(text: &str) -> Result<(Weekday, NaiveTime), CommandError> {
    let bad = || CommandError::Validation(format!("bad schedule time {text:?}"));
    let n: Vec<u32> = text
        .split(',')
        .map(|p| p.trim().parse().map_err(|_| bad()))
        .collect::<Result<_, _>>()?;
    let [day, hour, min] = n[..] else {
        return Err(bad());
    };
    let weekday = Weekday::try_from(((day + 6) % 7) as u8).map_err(|_| bad())?;
    let time = NaiveTime::from_hms_opt(hour, min, 0).ok_or_else(bad)?;
    Ok((weekday, time))
}

/// The rule for one Cold Turkey exception. A bare YouTube channel name also
/// gets its `@handle` form, which is the address YouTube redirects to.
fn exceptions(name: &str, raw: &str) -> Vec<ExceptionRule> {
    let mut rules = vec![exception(name, raw)];
    let path = raw.strip_suffix('*').unwrap_or(raw);
    if let Some(channel) = path.strip_prefix("youtube.com/")
        && !channel.is_empty()
        && !channel.contains(['/', '?', '*', '@'])
    {
        rules.push(exception(name, &format!("youtube.com/@{channel}*")));
    }
    rules
}

fn exception(name: &str, raw: &str) -> ExceptionRule {
    let exception_type = if raw == "file://*.*" {
        ExceptionType::LocalFiles
    } else {
        // A trailing `*` means "and what is under it", which `UrlPath` already is.
        let path = raw.strip_suffix('*').unwrap_or(raw);
        if path.contains('*') {
            ExceptionType::Wildcard(raw.to_string())
        } else if path.contains('/') {
            ExceptionType::UrlPath(path.to_string())
        } else {
            ExceptionType::Domain(path.to_string())
        }
    };
    ExceptionRule {
        id: id(&[name, "exception", raw]),
        exception_type,
        enabled: true,
    }
}

fn convert_block(name: &str, ct: &CtBlock) -> Result<BlockList, CommandError> {
    if ct.kind != "scheduled" {
        return Err(CommandError::Validation(format!(
            "{name:?} is a {:?} block; only scheduled blocks can be converted",
            ct.kind
        )));
    }
    let mut list = BlockList::new(name);
    list.id = id(&[name]);
    list.enabled = ct.enabled == "true";
    list.websites = ct
        .web
        .iter()
        .map(|d| WebsiteRule {
            id: id(&[name, "web", d]),
            ..WebsiteRule::domain(d)
        })
        .collect();
    list.applications = ct
        .apps
        .iter()
        .map(|a| AppRule {
            id: id(&[name, "app", a]),
            ..AppRule::executable(a.strip_prefix("file:").unwrap_or(a))
        })
        .collect();
    list.exceptions = ct
        .exceptions
        .iter()
        .flat_map(|e| exceptions(name, e))
        .collect();
    let time_slots = ct
        .schedule
        .iter()
        .map(|s| {
            let (day, start) = moment(&s.start)?;
            // The end's day is implied: Focuser wraps a slot that ends at or
            // before its start into the next day, as Cold Turkey's `1,0,0` does.
            let (_, end) = moment(&s.end)?;
            Ok(TimeSlot::new(day, start, end))
        })
        .collect::<Result<Vec<_>, CommandError>>()?;
    list.schedule = Some(Schedule {
        id: id(&[name, "schedule"]),
        name: name.to_string(),
        time_slots,
        enabled: true,
    });
    // `schedule,<method>,true`: the block cannot be switched off while a window runs.
    if ct.lock.starts_with("schedule,") && ct.lock.ends_with(",true") {
        list.scheduled_protection = Some(ScheduledProtection { lock: None });
    }
    Ok(list)
}

/// Convert the named blocks (all convertible ones when `only` is empty) and
/// return the Focuser configuration document as JSON.
pub fn convert(json: &str, only: &[String]) -> Result<String, CommandError> {
    let blocks: BTreeMap<String, serde_json::Value> = serde_json::from_str(json)
        .map_err(|e| CommandError::Validation(format!("not a Cold Turkey block list file: {e}")))?;
    let mut block_lists = Vec::new();
    for (name, value) in &blocks {
        if !only.is_empty() && !only.contains(name) {
            continue;
        }
        let ct: CtBlock = serde_json::from_value(value.clone())
            .map_err(|e| CommandError::Validation(format!("block {name:?}: {e}")))?;
        block_lists.push(convert_block(name, &ct)?);
    }
    if let Some(missing) = only.iter().find(|n| !blocks.contains_key(*n)) {
        return Err(CommandError::Validation(format!(
            "no block named {missing:?} in the file"
        )));
    }
    let document = ConfigDocument {
        version: CONFIG_FORMAT_VERSION,
        app: "Focuser".to_string(),
        exported_at: Utc::now(),
        block_lists,
    };
    serde_json::to_string_pretty(&document).map_err(|e| CommandError::Internal(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURE: &str = r#"{
      "Frozen Turkey":{"enabled":"false","type":"continuous","lock":"none","web":[],"exceptions":[],"apps":["frozen:lock"],"schedule":[]},
      "Distractions":{"enabled":"true","type":"scheduled","lock":"schedule,0,true",
        "web":["youtube.com","x.com"],
        "exceptions":["file://*.*","youtube.com/carmyspecial*","youtube.com/watch?v=*&list=wl*","example.org"],
        "apps":[],
        "schedule":[{"id":"0","startTime":"0,23,30","endTime":"1,0,0"},{"id":"1","startTime":"1,9,0","endTime":"1,13,0"}]}
    }"#;

    fn lists(only: &[&str]) -> Vec<BlockList> {
        let only: Vec<String> = only.iter().map(|s| s.to_string()).collect();
        let doc: ConfigDocument = serde_json::from_str(&convert(FIXTURE, &only).unwrap()).unwrap();
        doc.block_lists
    }

    #[test]
    fn converts_websites_exceptions_schedule_and_lock() {
        let lists = lists(&["Distractions"]);
        let l = &lists[0];
        assert!(l.enabled && l.scheduled_protection.is_some());
        assert_eq!(l.websites.len(), 2);
        let kinds: Vec<_> = l.exceptions.iter().map(|e| &e.exception_type).collect();
        assert!(matches!(kinds[0], ExceptionType::LocalFiles));
        assert!(matches!(kinds[1], ExceptionType::UrlPath(p) if p == "youtube.com/carmyspecial"));
        assert!(matches!(kinds[2], ExceptionType::UrlPath(p) if p == "youtube.com/@carmyspecial"));
        assert!(matches!(kinds[3], ExceptionType::Wildcard(_)));
        assert!(matches!(kinds[4], ExceptionType::Domain(d) if d == "example.org"));
        let slots = &l.schedule.as_ref().unwrap().time_slots;
        // Day 0 is Sunday; the Sunday-night slot wraps into Monday.
        assert_eq!(slots[0].day, Weekday::Sun);
        assert_eq!(slots[0].end, NaiveTime::MIN);
        assert_eq!(slots[1].day, Weekday::Mon);
    }

    #[test]
    fn sunday_night_slot_is_active_late_sunday() {
        let l = &lists(&["Distractions"])[0];
        let schedule = l.schedule.as_ref().unwrap();
        // 2026-10-25 is a Sunday.
        let at = |h| {
            chrono::NaiveDate::from_ymd_opt(2026, 10, 25)
                .unwrap()
                .and_hms_opt(h, 45, 0)
                .unwrap()
                .and_utc()
        };
        assert!(schedule.active_period_at(at(23)).is_some());
        assert!(schedule.active_period_at(at(12)).is_none());
    }

    #[test]
    fn ids_are_stable_across_runs() {
        assert_eq!(
            lists(&["Distractions"])[0].id,
            lists(&["Distractions"])[0].id
        );
    }

    #[test]
    fn unfiltered_conversion_rejects_non_scheduled_blocks() {
        assert!(convert(FIXTURE, &[]).is_err());
        assert!(convert(FIXTURE, &["Nope".to_string()]).is_err());
    }
}
