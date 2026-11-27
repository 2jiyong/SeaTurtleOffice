#!/bin/bash
# (내 컴퓨터에서) 서버 SSH(22)를 지금 내 공인 IP에서만 열리게 바꾼다. 집·카페를 옮겨 접속이 안 될 때.
# AWS CLI(서울 리전)가 설정돼 있어야 한다.
set -euo pipefail
GROUP=${GROUP:-seaturtle-office}
REGION=${AWS_REGION:-ap-northeast-2}
ip=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
[[ $ip =~ ^[0-9]+(\.[0-9]+){3}$ ]] || { echo "공인 IP를 알 수 없습니다: $ip"; exit 1; }

gid=$(aws ec2 describe-security-groups --region "$REGION" --filters "Name=group-name,Values=$GROUP" \
	--query 'SecurityGroups[0].GroupId' --output text)
[[ $gid == sg-* ]] || { echo "보안 그룹 $GROUP을 찾을 수 없습니다"; exit 1; }

old=$(aws ec2 describe-security-group-rules --region "$REGION" --filters "Name=group-id,Values=$gid" \
	--query 'SecurityGroupRules[?!IsEgress && FromPort==`22`].SecurityGroupRuleId' --output text)
if [[ -n $old && $old != None ]]; then
	# shellcheck disable=SC2086
	aws ec2 revoke-security-group-ingress --region "$REGION" --group-id "$gid" --security-group-rule-ids $old > /dev/null
	echo "지운 22번 규칙: $old"
fi
aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$gid" \
	--ip-permissions "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$ip/32,Description=ssh-from-my-ip}]" > /dev/null
echo "SSH 허용: $ip/32 ($gid)"
