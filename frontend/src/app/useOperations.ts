import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorMessage } from "../api/client";
import type { BagPage, Equipment, Journey, Summary } from "../types/contracts";
import { createStompClient, LiveFeed } from "../realtime/feed";
import type { Connection } from "../realtime/feed";

export type RestSync = "loading" | "refreshing" | "synced" | "stale";

interface Resource<T> {
  data: T | null;
  error: string;
  loading: boolean;
}
const empty = <T>(): Resource<T> => ({ data: null, error: "", loading: true });
export function useOperations(
  search: string,
  state: string,
  page: number,
  selected: string | null,
) {
  const [list, setList] = useState<Resource<BagPage>>(empty);
  const [summary, setSummary] = useState<Resource<Summary>>(empty);
  const [equipment, setEquipment] = useState<Resource<Equipment[]>>(empty);
  const [journey, setJourney] = useState<Resource<Journey>>(empty);
  const [connection, setConnection] = useState<Connection>("connecting");
  const [restSync, setRestSync] = useState<RestSync>("loading");
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const current = useRef({ search, state, page, selected });
  current.current = { search, state, page, selected };
  const feed = useRef<LiveFeed | null>(null);
  const runner = useRef<{
    pending: Promise<void> | null;
    again: boolean;
    mounted: boolean;
    epoch: number;
    abort: AbortController | null;
  }>({ pending: null, again: false, mounted: false, epoch: 0, abort: null });
  const refresh = useCallback((): Promise<void> => {
    const work = runner.current;
    if (!work.mounted) return Promise.resolve();
    setRestSync("refreshing");
    if (work.pending) {
      work.again = true;
      return work.pending;
    }
    work.pending = (async () => {
      let failed = false;
      do {
        setRestSync("refreshing");
        work.again = false;
        const query = current.current;
        const epoch = work.epoch;
        const abort = new AbortController();
        work.abort = abort;
        failed = false;
        const isCurrent = () =>
          work.mounted && work.epoch === epoch && !abort.signal.aborted &&
          current.current.search === query.search && current.current.state === query.state &&
          current.current.page === query.page && current.current.selected === query.selected;
        async function load<T>(
          get: () => Promise<T>,
          set: React.Dispatch<React.SetStateAction<Resource<T>>>,
        ) {
          try {
            const data = await get();
            if (isCurrent()) set({ data, loading: false, error: "" });
          } catch (error) {
            failed = true;
            if (isCurrent())
              set((old) => ({
                ...old,
                loading: false,
                error: errorMessage(error),
              }));
          }
        }
        await Promise.all([
          load(
            () => api.bags(query.search, query.state, query.page, abort.signal),
            setList,
          ),
          load(() => api.summary(abort.signal), setSummary),
          load(
            async () => (await api.equipment(abort.signal)).items,
            setEquipment,
          ),
          ...(query.selected
            ? [
                load(
                  () => api.journey(query.selected!, abort.signal),
                  setJourney,
                ),
              ]
            : []),
        ]);
        if (isCurrent()) {
          setRestSync(failed ? "stale" : "synced");
          if (!failed) setUpdatedAt(new Date().toISOString());
        } else if (work.mounted) {
          // Aborted/obsolete reads must not restore freshness; reload the current query.
          work.again = true;
        }
      } while (work.again && work.mounted);
      if (failed && work.mounted)
        throw new Error("Some authoritative data could not be refreshed");
    })().finally(() => {
      work.pending = null;
    });
    return work.pending;
  }, []);
  useEffect(() => {
    runner.current.mounted = true;
    runner.current.epoch++;
    const live = new LiveFeed(createStompClient(), refresh, setConnection);
    feed.current = live;
    live.start();
    // State events omit command acknowledgements and rejected scans; REST polling also discovers new/off-page bags.
    const timer = window.setInterval(() => {
      void refresh().catch(() => {});
    }, 10000);
    return () => {
      runner.current.mounted = false;
      runner.current.epoch++;
      runner.current.abort?.abort();
      window.clearInterval(timer);
      void live.stop();
    };
  }, [refresh]);
  useEffect(() => {
    setList(empty());
  }, [search, state, page]);
  useEffect(() => {
    setJourney(empty());
  }, [selected]);
  useEffect(() => {
    runner.current.abort?.abort();
    void refresh().catch(() => {});
  }, [search, state, page, selected, refresh]);
  useEffect(() => {
    feed.current?.setBags(
      [
        ...new Set([
          ...(list.data?.items.map((item) => item.bag.bagId) ?? []),
          ...(selected ? [selected] : []),
        ]),
      ].sort(),
    );
  }, [list.data, selected]);
  return {
    list,
    summary,
    equipment,
    journey:
      journey.data?.bag.bagId === selected
        ? journey
        : { ...journey, data: null },
    connection,
    restSync,
    updatedAt,
    refresh,
  };
}
