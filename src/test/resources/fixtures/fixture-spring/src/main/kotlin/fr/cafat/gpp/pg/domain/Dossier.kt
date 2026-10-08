package fr.cafat.gpp.pg.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.OneToOne
import jakarta.persistence.Table

/** Dossier : la colonne id est portée par le champ histoNumero. */
@Entity
@Table(name = "gpp_dossier", schema = "sgengpp")
class Dossier(
    @Id
    @Column(name = "id")
    var histoNumero: Long? = null,

    @Column(name = "statut", length = 10, nullable = false)
    var statut: String = "",

    @OneToOne(mappedBy = "dossier")
    var personne: PersonnePhysique? = null,
) {
    @Transient
    var commentaire: String? = null
}
