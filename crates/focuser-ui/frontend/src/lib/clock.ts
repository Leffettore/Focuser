/** 12- or 24-hour display, kept per device: a phone and a desktop can differ. */
const KEY = "focuser.clock24";

export const uses24Hour = () => {
  try {
    return localStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
};

export const setUses24Hour = (on: boolean) => {
  try {
    localStorage.setItem(KEY, on ? "1" : "0");
  } catch {
    // Private mode: the choice just lasts until the page is closed.
  }
};
