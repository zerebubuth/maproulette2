package org.maproulette.session.dal

import anorm._
import anorm.SqlParser._
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.maproulette.session.{Location, OSMProfile, User}
import play.api.db.DB
import play.api.Play.current
import org.maproulette.cache.CacheManager
import play.api.libs.json.JsValue
import play.api.libs.oauth.RequestToken

/**
  * @author cuthbertm
  */
object UserDAL {

  import org.maproulette.utils.AnormExtension._

  val cacheManager = new CacheManager[Long, User]

  val parser: RowParser[User] = {
    get[Long]("users.id") ~
      get[Long]("users.osm_id") ~
      get[DateTime]("users.created") ~
      get[DateTime]("users.modified") ~
      get[String]("users.theme") ~
      get[DateTime]("users.osm_created") ~
      get[String]("users.display_name") ~
      get[Option[String]]("users.description") ~
      get[Option[String]]("users.avatar_url") ~
      get[Option[String]]("users.api_key") ~
      get[String]("users.oauth_token") ~
      get[String]("users.oauth_secret") map {
      case id ~ osmId ~ created ~ modified ~ theme ~ osmCreated ~ displayName ~ description ~
        avatarURL ~ apiKey ~ oauthToken ~ oauthSecret =>
        // If the modified date is too old, then lets update this user information from OSM
        new User(id, created, modified, theme,
          OSMProfile(osmId, displayName, description.getOrElse(""), avatarURL.getOrElse(""),
            Location(0, 0), osmCreated, RequestToken(oauthToken, oauthSecret)), apiKey)
    }
  }

  def findByID(implicit id: Long): Option[User] = cacheManager.withOptionCaching { () =>
    DB.withConnection { implicit c =>
      SQL"""SELECT * FROM users WHERE id = $id""".as(parser.*).headOption
    }
  }

  def findByOSMID(implicit id: Long): Option[User] = cacheManager.withOptionCaching { () =>
    DB.withConnection { implicit c =>
      SQL"""SELECT * FROM users WHERE osm_id = $id""".as(parser.*).headOption
    }
  }

  def findByAPIKey(apiKey:String)(implicit id:Long) : Option[User] = cacheManager.withOptionCaching { () =>
    DB.withConnection { implicit c =>
      SQL"""SELECT * FROM users WHERE id = $id AND api_key = ${apiKey}""".as(parser.*).headOption
    }
  }

  def matchByRequestTokenAndId(id: Long, requestToken: RequestToken): Option[User] = {
    implicit val userId = id
    val user = cacheManager.withOptionCaching { () =>
      DB.withConnection { implicit c =>
        SQL"""SELECT * FROM users WHERE id = $id AND oauth_token = ${requestToken.token}
             AND oauth_secret = ${requestToken.secret}""".as(parser.*).headOption
      }
    }
    user match {
      case Some(u) =>
        // double check that the token and secret still match, in case it came from the cache
        if (StringUtils.equals(u.osmProfile.requestToken.token, requestToken.token) &&
          StringUtils.equals(u.osmProfile.requestToken.secret, requestToken.secret)) {
          Some(u)
        } else {
          None
        }
      case None => None
    }
  }

  def matchByRequestToken(requestToken: RequestToken): Option[User] = {
    DB.withConnection { implicit c =>
      SQL"""SELECT * FROM users WHERE oauth_token = ${requestToken.token}
           AND oauth_secret = ${requestToken.secret}""".as(parser.*).headOption
    }
  }

  def upsert(user: User): Option[User] = cacheManager.withOptionCaching { () =>
    DB.withTransaction { implicit c =>
      SQL"""WITH upsert AS (UPDATE users SET osm_id = ${user.osmProfile.id}, osm_created = ${user.osmProfile.created},
                              display_name = ${user.osmProfile.displayName}, description = ${user.osmProfile.description},
                              avatar_url = ${user.osmProfile.avatarURL},
                              oauth_token = ${user.osmProfile.requestToken.token},
                              oauth_secret = ${user.osmProfile.requestToken.secret},
                              theme = ${user.theme}
                            WHERE id = ${user.id} OR osm_id = ${user.osmProfile.id} RETURNING *)
            INSERT INTO users (osm_id, osm_created, display_name, description,
                               avatar_url, oauth_token, oauth_secret, theme)
            SELECT ${user.osmProfile.id}, ${user.osmProfile.created}, ${user.osmProfile.displayName},
                    ${user.osmProfile.description}, ${user.osmProfile.avatarURL},
                    ${user.osmProfile.requestToken.token}, ${user.osmProfile.requestToken.secret},
                    ${user.theme}
            WHERE NOT EXISTS (SELECT * FROM upsert)""".executeUpdate()
      SQL"""SELECT * FROM users WHERE osm_id = ${user.osmProfile.id}""".as(parser.*).headOption
    }
  }

  /**
    * Only certain values are allowed to be updated for the user.
    *
    * @param value
    * @param id
    * @return
    */
  def update(value:JsValue)(implicit id:Long): Option[User] = {
    cacheManager.withUpdatingCache(Long => findByID) { implicit cachedItem =>
      DB.withTransaction { implicit c =>
        val apiKey = (value \ "apiKey").asOpt[String].getOrElse(cachedItem.apiKey.getOrElse(""))
        val displayName = (value \ "osmProfile" \ "displayName").asOpt[String].getOrElse(cachedItem.osmProfile.displayName)
        val description = (value \ "osmProfile" \ "description").asOpt[String].getOrElse(cachedItem.osmProfile.description)
        val avatarURL = (value \ "osmProfile" \ "avatarURL").asOpt[String].getOrElse(cachedItem.osmProfile.avatarURL)
        val token = (value \ "osmProfile" \ "token").asOpt[String].getOrElse(cachedItem.osmProfile.requestToken.token)
        val secret = (value \ "osmProfile" \ "secret").asOpt[String].getOrElse(cachedItem.osmProfile.requestToken.secret)
        val theme = (value \ "theme").asOpt[String].getOrElse(cachedItem.theme)

        SQL"""UPDATE users SET api_key = $apiKey, display_name = $displayName, description = $description,
                avatar_url = $avatarURL, oauth_token = $token, oauth_secret = $secret
              WHERE id = $id RETURNING *""".as(parser.*).headOption
      }
    }
  }

  def delete(implicit id: Long) = {
    implicit val ids = List(id)
    cacheManager.withCacheIDDeletion { () =>
      DB.withConnection { implicit c =>
        SQL"""DELETE FROM users WHERE id = $id"""
      }
    }
  }
}
