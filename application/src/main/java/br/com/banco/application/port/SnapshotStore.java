package br.com.banco.application.port;

import br.com.banco.domain.ContaSnapshot;

import java.util.Optional;

/** Snapshots são otimização descartável: o sistema deve funcionar corretamente sem eles. */
public interface SnapshotStore {

    /** Snapshot compatível com o esquema atual, se houver. */
    Optional<ContaSnapshot> find(String streamId);

    /** Grava apenas se for mais novo que o existente. */
    void save(String streamId, ContaSnapshot snapshot);

    SnapshotStore NONE = new SnapshotStore() {
        @Override public Optional<ContaSnapshot> find(String streamId) { return Optional.empty(); }
        @Override public void save(String streamId, ContaSnapshot snapshot) { }
    };
}

