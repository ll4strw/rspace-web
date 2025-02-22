package com.researchspace.service.cloud;

import com.researchspace.model.Group;
import com.researchspace.model.User;
import java.util.List;
import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;

/**
 * Sends notifications and messages concerning inviting external or internal users to join a group
 * or access a shared record.
 */
public interface CloudNotificationManager {

  /**
   * Send a join group request to the user.
   *
   * @param user
   * @param group
   */
  void sendJoinGroupRequest(User user, Group group);

  /**
   * Send a create group request to the target (user) with an attached list of e-mails (invited
   * users). This request is generated by the source (user).
   *
   * @param source
   * @param target
   * @param emails
   * @param groupName
   */
  void sendCreateGroupRequest(User source, User target, List<String> emails, String groupName);

  /**
   * Send a share record request to the target (user). This request is generated by the source
   * (user).
   *
   * @param source
   * @param target
   * @param recordId
   * @param permission
   */
  void sendShareRecordRequest(User source, User target, Long recordId, String permission);

  /**
   * Send an email to the invited user email (Checking if invited user is a temporary user or an
   * existing user). Invited user will be a normal user within the group.
   *
   * @param creator
   * @param invitedUser
   * @param group
   * @param request an optional current {@link HttpServletRequest}. Can be <code>null</code>.
   */
  void sendJoinGroupInvitationEmail(
      User creator, User invitedUser, Group group, HttpServletRequest request);

  /**
   * Send an email to the invited user email (Checking if invited user is a temporary user or an
   * existing user). Invited user will be the Principal investigator within the group.
   *
   * @param creator
   * @param invitedUser
   * @param groupName
   * @param request an optional current {@link HttpServletRequest}. Can be <code>null</code>.
   * @throws MessagingException
   */
  void sendPIInvitationEmail(
      User creator, User invitedUser, String groupName, HttpServletRequest request);

  /**
   * Send an email to the invited user email (Share Record).
   *
   * @param creator
   * @param invited
   * @param recordName
   * @param request an optional current {@link HttpServletRequest}. Can be <code>null</code>.
   * @throws MessagingException
   */
  void sendShareRecordInvitationEmail(
      User creator, User invited, String recordName, HttpServletRequest request);
}
