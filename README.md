# Ourlet : ta boutique

1. Ouvre ce dépôt dans un Codespace (bouton vert **Code**, onglet **Codespaces**).
2. Dans le terminal, tape `./ouvrir` et donne un nom à ta boutique.
3. Ta page **Ma boutique** s’ouvre : ce que voient tes clients, ce que le robot vérifie, ta place.

Ta boutique est dans `boutique/`. Chaque enregistrement de ton code la met en ligne aussitôt.
Le contrat que le robot vérifie :

- `GET /produits` renvoie la liste des variantes en JSON : `[{"id":1,"produit":"Hoodie oversize","taille":"M","prix":59}, ...]`
- `POST /commandes` reçoit `{"variante":1,"client":"Léa"}` et répond `201` si la commande est confirmée, `409` s’il n’y a plus de stock.
